package ge.momento.booth

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.text.InputType
import android.content.Intent
import android.net.Uri
import java.io.File
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.ViewGroup
import android.os.*
import android.view.View
import android.widget.*
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.Context
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var ble: GoProBle
    private lateinit var importer: GoProMediaImporter
    private var importingFromGoPro=false
    private var pendingWifiImport=false
    private var captureStartedAtMs=0L
    private lateinit var importStatus: TextView
    private lateinit var previewRecorder: GoProPreviewRecorder
    private lateinit var exporter: VideoExporter
    private lateinit var previewMp4Exporter: PreviewMp4Exporter
    private var lastPreviewFile: File? = null
    private var reservingLink = false
    private var uploadAfterReserve = false
    private lateinit var api: Momento360Api
    private lateinit var pendingUploads: PendingUploadQueue
    private lateinit var preparedVideos: PreparedVideoQueue
    private var reservingPrepared = false
    private var retryLoop: Runnable? = null
    private lateinit var pendingLabel: TextView
    private var session: Momento360Api.Session? = null
    private lateinit var linkStatus: TextView
    private lateinit var shareButton: Button
    private lateinit var uploadButton: Button
    private lateinit var storageSelector: RadioGroup
    private var uploading = false
    private var videoInput: Uri? = null
    private var outputFile: File? = null
    private var selectedPlan: RecordingPlan? = null
    private lateinit var mediaLabel: TextView
    private lateinit var exportButton: Button
    private lateinit var saveButton: Button
    private var exporting = false
    private val handler = Handler(Looper.getMainLooper())
    private var connected = false
    private var recording = false
    private var busy = false
    private var stoppingTask: Runnable? = null
    private lateinit var state: TextView
    private lateinit var planView: TextView
    private lateinit var start: Button
    private lateinit var time: SeekBar
    private lateinit var slowFrom: SeekBar
    private lateinit var slowLength: SeekBar
    private lateinit var speed: Spinner
    private lateinit var timeLabel: TextView
    private lateinit var fromLabel: TextView
    private lateinit var lengthLabel: TextView
    // 360Tok controller is independent from the existing GoPro v0.7 pipeline.
    private lateinit var tok: Tok360Ble
    private lateinit var boothUi: Booth360Ui
    private lateinit var boothScroll: ScrollView
    private lateinit var goProScroll: ScrollView
    private lateinit var bottom360: Button
    private lateinit var bottomGoPro: Button
    private var syncing360 = false
    private var pending360 = false
    private var goProCaptureActive = false
    private var motorEverStarted = false
    private var current360Seconds = 0
    private var syncSessionId = 0
    private var syncStopTimeout: Runnable? = null
    private var motorOnlyTimeout: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exporter = VideoExporter(this)
        previewMp4Exporter = PreviewMp4Exporter(this)
        lastPreviewFile = filesDir.listFiles()?.filter { it.isFile && it.name.startsWith("MOMENTO_preview_") && it.extension == "ts" }?.maxByOrNull { it.lastModified() }
        api = Momento360Api(this)
        importer = GoProMediaImporter(this)
        pendingUploads = PendingUploadQueue(this)
        preparedVideos = PreparedVideoQueue(this)
        session = api.getSession()
        outputFile = api.getExportedFile()
        ble = GoProBle(this,
            { message -> runOnUiThread { if (::state.isInitialized) state.text = message } },
            { isConnected -> runOnUiThread {
                connected = isConnected
                if (::boothUi.isInitialized) boothUi.setCameraConnection(isConnected)
                if (!isConnected && syncing360 && goProCaptureActive) {
                    // We no longer know the camera state; do not claim successful REC STOP.
                    if (::state.isInitialized) state.text = "GoPro გათიშულია — კამერის REC გადაამოწმე"
                }
                refresh()
            } }
        )
        previewRecorder = GoProPreviewRecorder(this)
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Booth360Ui.BG)
        }
        val contentFrame = FrameLayout(this)
        shell.addView(contentFrame, LinearLayout.LayoutParams(-1, 0, 1f))
        boothUi = Booth360Ui(
            this,
            onConnect = { if (permissions()) tok.connect() },
            onToggle = { if (pending360 || syncing360 || tok.motorOn == true) stopSynchronized360() else startSynchronized360(0) },
            onTimed = { seconds -> startSynchronized360(seconds) },
            onCustom = { chooseBoothTime() },
            onDirection = { value -> if (tok.connected) { tok.send(3, if (value == 0) 1 else 0); boothUi.setDirection(value) } else boothUi.showMessage("360Tok დაკავშირებული არ არის") },
            onSpeed = { value -> if (tok.connected) { tok.send(2, value); boothUi.setSpeed(value) } else boothUi.showMessage("360Tok დაკავშირებული არ არის") },
            onLight = { on -> if (tok.connected) { tok.send(6, if (on) 1 else 0); boothUi.setLight(on) } else boothUi.showMessage("360Tok დაკავშირებული არ არის") },
            onMode = { mode -> if (tok.connected) { tok.send(7, mode); boothUi.setMode(mode) } else boothUi.showMessage("360Tok დაკავშირებული არ არის") },
            onBrightness = { value -> if (tok.connected) tok.send(8, value) else boothUi.showMessage("360Tok დაკავშირებული არ არის") },
            onCameraTab = { showTab(false) }
        )
        boothScroll = boothUi.screen
        contentFrame.addView(boothScroll, FrameLayout.LayoutParams(-1, -1))
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(18), dp(15), dp(22))
            setBackgroundColor(Booth360Ui.BG)
        }
        goProScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            setBackgroundColor(Booth360Ui.BG)
            addView(root)
            visibility = View.GONE
        }
        contentFrame.addView(goProScroll, FrameLayout.LayoutParams(-1, -1))
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(9))
            setBackgroundColor(Color.rgb(10, 23, 41))
        }
        bottom360 = bottomTab("◉   360", true)
        bottomGoPro = bottomTab("▣   GoPro", false)
        bottom.addView(bottom360, LinearLayout.LayoutParams(0, dp(55), 1f))
        bottom.addView(bottomGoPro, LinearLayout.LayoutParams(0, dp(55), 1f).apply { leftMargin=dp(10) })
        bottom360.setOnClickListener { showTab(true) }
        bottomGoPro.setOnClickListener { showTab(false) }
        shell.addView(bottom, LinearLayout.LayoutParams(-1, dp(73)))
        setContentView(shell)
        tok = Tok360Ble(this,
            { message -> runOnUiThread { android.util.Log.d("MOMENTO360", message) } },
            { isConnected, motorState, motorSpeed -> runOnUiThread {
                boothUi.setMotorConnection(isConnected)
                boothUi.setMotorState(motorState, motorSpeed)
                if (isConnected && motorState == true && syncing360) motorEverStarted=true
                if (syncing360 && motorEverStarted && motorState == false) {
                    stopSynchronized360(motorAlreadyStopped = true)
                }
                if (!isConnected && syncing360) {
                    // Camera must still be stopped if motor connection is lost.
                    stopSynchronized360(motorAlreadyStopped = true)
                }
            } }
        )
        fun label(value: String, size: Float = 17f): TextView = TextView(this).apply { text = value; textSize = size; setTextColor(Color.WHITE); setPadding(0, 14, 0, 8); root.addView(this) }
        label("MOMENTO 360", 30f)
        label("GoPro HERO9 • სპინერის მართვა ცალკე პულტით", 14f)
        state = label("GoPro დაკავშირებული არ არის", 15f)
        label("momento.ge — საიტთან დაკავშირება", 19f)
        val keyField = EditText(this).apply {
            hint = "360 API გასაღები (WordPress → Momento Gallery → 360 ვიდეოები)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY); setSingleLine(true)
            root.addView(this)
        }
        Button(this).apply {
            text = "API გასაღების შენახვა"; root.addView(this)
            setOnClickListener {
                if (keyField.text.toString().trim().length < 32) { keyField.error = "ჩასვი საიტის 360 API გასაღები"; return@setOnClickListener }
                api.saveKey(keyField.text.toString())
                keyField.text.clear()
                Toast.makeText(this@MainActivity, "360 API გასაღები შენახულია", Toast.LENGTH_SHORT).show()
            }
        }
        Button(this).apply {
            text = "✓ momento.ge კავშირის შემოწმება"; root.addView(this)
            setOnClickListener {
                state.text = "WordPress 360 მოდულთან კავშირის შემოწმება…"
                api.checkServer { result ->
                    result.onSuccess { (storage, sms) ->
                        state.text = "momento.ge კავშირი მუშაობს. Google Drive: " +
                            (if (storage) "მზადაა" else "შეცდომა") +
                            "; SMSOffice: " + (if (sms) "გამართულია" else "გადასამოწმებელია")
                    }.onFailure { e -> state.text = "საიტთან კავშირი ვერ დადასტურდა: ${e.message}" }
                }
            }
        }
        label("გადაღების რეჟიმი", 19f)
        storageSelector = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            val sd = RadioButton(this@MainActivity).apply { id=1001; text="microSD — მაღალი ხარისხის ჩაწერა"; setTextColor(Color.WHITE) }
            val noSd = RadioButton(this@MainActivity).apply { id=1002; text="SD-ის გარეშე — GoPro Wi-Fi Preview (სატესტო, 30fps)"; setTextColor(Color.WHITE) }
            addView(sd); addView(noSd); check(1001); root.addView(this)
            setOnCheckedChangeListener { _, selected ->
                if (selected==1002) AlertDialog.Builder(this@MainActivity)
                    .setTitle("SD-ის გარეშე")
                    .setMessage("START ახლა დაიწყებს GoPro Wi-Fi Preview ნაკადის ტელეფონში ჩაწერის მცდელობას.\n\nჯერ ტელეფონის Wi-Fi დაუკავშირე GoPro-ს საკუთარ ქსელს. ეს არის 30fps საცდელი რეჟიმი, არა 120/240fps Slow Motion. SD ბარათის გარეშე მხარდაჭერა დამოკიდებულია კამერის firmware-ზე და დასატესტია.")
                    .setPositiveButton("გასაგებია",null).show()
                refresh()
            }
        }
        val connect = Button(this).apply { text = "GO PRO - დაკავშირება"; root.addView(this); setOnClickListener { if (permissions()) ble.scanAndConnect() } }
        Button(this).apply {
            text = "GoPro Wi-Fi-სთან დაკავშირება (SD-ის გარეშე რეჟიმი)"; root.addView(this)
            setOnClickListener {
                if (connected) ble.enableWifiAp { ok -> runOnUiThread {
                    Toast.makeText(this@MainActivity,
                        if (ok) "Wi-Fi-ის ჩართვის ბრძანება მიღებულია; ქსელი რამდენიმე წამში გამოჩნდება" else "GoPro Wi-Fi ვერ ჩაირთო ავტომატურად",
                        Toast.LENGTH_LONG).show()
                } }
                AlertDialog.Builder(this@MainActivity).setTitle("GoPro-ს Wi-Fi ქსელი")
                    .setMessage("1. კამერაზე ჩართე Wireless Connections → Connect Device → Quik App.\n2. ტელეფონის Wi-Fi პარამეტრებში აირჩიე GoPro-ს ქსელი და შეიყვანე კამერის Wi-Fi პაროლი.\n3. ტელეფონი შესაძლოა გაჩვენებდეს «ინტერნეტის გარეშე» — დარჩი GoPro-ს ქსელზე.\n4. დაბრუნდი აპლიკაციაში და დააჭირე START-ს.\n\nWi-Fi SSID/პაროლი კამერის Connections → Camera Info-შია. ინტერნეტში ატვირთვას მობილური მონაცემები დასჭირდება.")
                    .setPositiveButton("Wi-Fi პარამეტრების გახსნა") { _, _ ->
                        startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
                    }.setNegativeButton("დახურვა", null).show()
            }
        }
        label("GoPro — კამერის ვიდეო პარამეტრები", 19f)
        label("შეცვალე მხოლოდ გადაღების დაწყებამდე. ზოგი FPS და გარჩევადობის კომბინაცია HERO9-ზე მიუწვდომელია.", 12f)
        val cameraRes = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("1080p", "2.7K", "4K", "5K"))
            root.addView(this)
        }
        val cameraFps = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("30 FPS", "60 FPS", "120 FPS", "240 FPS"))
            setSelection(2)
            root.addView(this)
        }
        Button(this).apply {
            text = "GoPro-ზე VIDEO პარამეტრების დაყენება"
            root.addView(this)
            setOnClickListener {
                if (!connected || recording || busy) {
                    state.text = "ვიდეო პარამეტრების შესაცვლელად GoPro უნდა იყოს დაკავშირებული და გადაღება გამორთული."
                    return@setOnClickListener
                }
                val resolution = intArrayOf(9,4,1,24)[cameraRes.selectedItemPosition]
                val fps = intArrayOf(8,5,1,0)[cameraFps.selectedItemPosition]
                state.text = "გარჩევადობის დაყენება…"
                ble.setVideoSetting(2, resolution, "რეზოლუცია შენახულია") { ok -> runOnUiThread {
                    if (!ok) {state.text="GoPro-მ გარჩევადობა ვერ შეცვალა — სცადე სხვა ვიდეო Preset";return@runOnUiThread}
                    state.text="FPS პარამეტრის დაყენება…"
                    ble.setVideoSetting(3, fps, "FPS შენახულია") { fpsOk -> runOnUiThread {
                        state.text=if(fpsOk) "GoPro ვიდეო პარამეტრები შენახულია"
                                   else "FPS მიუწვდომელია არჩეული რეზოლუციისთვის"
                    } }
                } }
            }
        }
        label("საბოლოო ვიდეოს ხანგრძლივობა")
        timeLabel = label("10 წამი", 14f)
        time = SeekBar(this).apply { max = 55; progress = 5; root.addView(this) } // 5..60
        label("Slow Motion-ის დაწყება საბოლოო ვიდეოში")
        fromLabel = label("3 წამზე", 14f)
        slowFrom = SeekBar(this).apply { max = 9; progress = 3; root.addView(this) }
        label("Slow Motion-ის ხანგრძლივობა საბოლოო ვიდეოში")
        lengthLabel = label("3 წამი", 14f)
        slowLength = SeekBar(this).apply { max = 6; progress = 2; root.addView(this) }
        label("შენელების სიჩქარე")
        speed = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("0.5×", "0.25×", "0.125×"))
            setSelection(1); root.addView(this)
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) { refresh() }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        }
        planView = label("", 16f)
        start = Button(this).apply { text = "START"; root.addView(this); setOnClickListener { if (recording) stopRecording() else startRecording() } }
        label("GoPro → Google Drive • ავტომატური MP4",19f)
        label("STOP-ის შემდეგ: GoPro MP4 → Slow Motion → Movies/MOMENTO360 → ატვირთვის რიგი. პირველ Wi-Fi დაკავშირებაზე Android-ის დასტური შესაძლოა აუცილებელი იყოს.",13f)
        importStatus=label("GoPro Wi-Fi ჯერ არ შემოწმებულა — მონაცემების შენახვა არ ნიშნავს დაკავშირებას.",14f)
        Button(this).apply {
            text="GoPro Wi-Fi მონაცემები • ერთჯერადი გამართვა"
            root.addView(this)
            setOnClickListener{showWifiCredentialsDialog(false)}
        }
        Button(this).apply {
            text="⟳ GoPro-სთან დაკავშირება და ბოლო MP4-ის გადმოტანა"
            root.addView(this)
            setOnClickListener{startGoProImport(false)}
        }
        label("GoPro-ს Wi-Fi-ს პირველად Android-ის ფანჯარაში დადასტურება შეიძლება დასჭირდეს. ვიდეოს ჩამოტვირთვის შემდეგ აპლიკაცია Wi-Fi კავშირს ათავისუფლებს, რომ ინტერნეტი დაბრუნდეს.",12f)
        label("ვიდეოს დამუშავება და შენახვა", 19f)
        label("microSD: GoPro MP4 ავტომატურად გადმოდის ტელეფონში STOP-ის შემდეგ. SD-ის გარეშე: Preview სატესტო რეჟიმია.", 13f)
        val pick = Button(this).apply { text = "1. ვიდეოს არჩევა ტელეფონიდან"; root.addView(this) }
        mediaLabel = label("ვიდეო ჯერ არ არის არჩეული", 14f)
        Button(this).apply {
            text = "ბოლო Preview-ის ხელახლა დამუშავება (გადაღების გარეშე)"
            root.addView(this)
            setOnClickListener {
                val original = lastPreviewFile
                if (original == null || !original.isFile) {
                    Toast.makeText(this@MainActivity, "ადრინდელი Preview ჩანაწერი ვერ მოიძებნა", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                if (recording || busy || exporting || uploading) return@setOnClickListener
                videoInput = Uri.fromFile(original)
                val saved = getSharedPreferences("momento_preview", MODE_PRIVATE)
                selectedPlan = if (!saved.contains("total")) plan() else runCatching {
                    RecordingPlan(saved.getInt("total", 10), saved.getInt("from", 3),
                        saved.getInt("slow", 3), java.lang.Double.longBitsToDouble(saved.getLong("speed", java.lang.Double.doubleToLongBits(0.25))))
                }.getOrElse { plan() }
                mediaLabel.text = "არსებული ${original.name} მუშავდება…"
                exportSelected()
            }
        }
        Button(this).apply {
            text = "Preview TS-ის შენახვა (ორიგინალი / ავარიული ასლი)"
            root.addView(this)
            setOnClickListener {
                val original = lastPreviewFile
                if (original == null || !original.isFile) {
                    Toast.makeText(this@MainActivity, "Preview ჩანაწერი არ მოიძებნა", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/octet-stream"
                    putExtra(Intent.EXTRA_TITLE, original.name)
                }
                startActivityForResult(intent, 903)
            }
        }
        pick.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "video/*"
            }
            startActivityForResult(intent, 901)
        }
        exportButton = Button(this).apply {
            text = "2. SLOW MOTION MP4-ის შექმნა"; root.addView(this)
            setOnClickListener { exportSelected() }
        }
        saveButton = Button(this).apply {
            text = "3. მზა MP4-ის შენახვა"; root.addView(this)
            setOnClickListener {
                if (outputFile == null) return@setOnClickListener
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "video/mp4"
                    putExtra(Intent.EXTRA_TITLE, outputFile!!.name)
                }
                startActivityForResult(intent, 902)
            }
        }
        label("momento.ge — Google Drive ვიდეოს გაზიარება", 19f)
        linkStatus = label(session?.let { "წინა ვიდეოს ბმული აღდგენილია: ${it.url}" }
            ?: "ბმული ჯერ არ შექმნილა. გადაღების STOP-ის შემდეგ სერვერზე ავტომატურად შეიქმნება.", 14f)
        shareButton = Button(this).apply { text = "ლინკი / QR კოდი / SMS"; root.addView(this); setOnClickListener { openShareDialog() } }
        uploadButton = Button(this).apply { text = "MP4-ის Google Drive-ზე ატვირთვა"; root.addView(this); setOnClickListener { uploadReadyFile() } }
        pendingLabel = label("", 14f)
        Button(this).apply {
            text = "⟳ გადაუტვირთავი ვიდეოების ატვირთვის გაგრძელება"
            root.addView(this)
            setOnClickListener { uploadNextPending() }
        }
        Button(this).apply {
            text = "ვიდეოს ბმულის შექმნა / ატვირთვის ხელახლა ცდა"
            root.addView(this)
            setOnClickListener { reserveLink(autoUpload = outputFile != null) }
        }
        label("შესაძლებელია ვიდეოს ბმულის გაზიარება მანამდეც, სანამ ფაილი აიტვირთება. ბმულზე ამ დროს გამოჩნდება ატვირთვის მოლოდინის გვერდი.", 13f)
        label("ექსპორტი აუდიოს გარეშეა. რეკომენდებულია GoPro-ს 1080p/120 ან 240fps ჩანაწერი; 0.125× სიჩქარისთვის 240fps უკეთესია. საბოლოო დრო შეიძლება 1–2 კადრით განსხვავდებოდეს კოდეკის დამრგვალების გამო.", 13f)
        val changed = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) { refresh() }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        }
        time.setOnSeekBarChangeListener(changed); slowFrom.setOnSeekBarChangeListener(changed); slowLength.setOnSeekBarChangeListener(changed)
        styleGoProTab(root)
        showTab(true)
        refresh()
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun roundBackground(color: Int, stroke: Int = 0xff28466B.toInt(), radius: Int = 16): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), stroke)
    }

    private fun bottomTab(text: String, selected: Boolean): Button = Button(this).apply {
        isAllCaps = false
        this.text = text
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        minimumHeight = 0
        minHeight = 0
        setTextColor(if (selected) Color.WHITE else Booth360Ui.MUTED)
        background = if (selected) roundBackground(0xff1466C5.toInt(), 0xff238CF0.toInt())
                     else roundBackground(0xff182B45.toInt(), 0xff27405C.toInt())
    }

    private fun showTab(show360: Boolean) {
        if (!::boothScroll.isInitialized || !::goProScroll.isInitialized) return
        boothScroll.visibility = if (show360) View.VISIBLE else View.GONE
        goProScroll.visibility = if (show360) View.GONE else View.VISIBLE
        bottom360.background = roundBackground(if (show360) 0xff1466C5.toInt() else 0xff182B45.toInt())
        bottomGoPro.background = roundBackground(if (show360) 0xff182B45.toInt() else 0xff1466C5.toInt())
        bottom360.setTextColor(if (show360) Color.WHITE else Booth360Ui.MUTED)
        bottomGoPro.setTextColor(if (show360) Booth360Ui.MUTED else Color.WHITE)
    }

    private fun styleGoProTab(root: LinearLayout) {
        val widgets = (0 until root.childCount).map { root.getChildAt(it) }
        root.removeAllViews()
        val heading = TextView(this).apply {
            text = "GoPro HERO9"
            textSize = 27f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Booth360Ui.WHITE)
            setPadding(dp(4), dp(7), dp(4), dp(10))
        }
        root.addView(heading)
        val groupTitles = setOf(
            "momento.ge — საიტთან დაკავშირება",
            "გადაღების რეჟიმი",
            "საბოლოო ვიდეოს ხანგრძლივობა",
            "GoPro → Google Drive • ავტომატური MP4",
            "ვიდეოს დამუშავება და შენახვა",
            "momento.ge — Google Drive ვიდეოს გაზიარება"
        )
        var section: LinearLayout? = null
        fun newCard(): LinearLayout {
            val card = LinearLayout(this).apply {
                orientation=LinearLayout.VERTICAL
                setPadding(dp(15),dp(15),dp(15),dp(16))
                background=roundBackground(0xff11213A.toInt(),0xff2B456B.toInt(),22)
            }
            root.addView(card,LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(13)})
            return card
        }
        for (view in widgets) {
            if (view is TextView && view.text.toString() == "MOMENTO 360") continue
            if (view is TextView && view.text.toString().contains("სპინერის მართვა ცალკე პულტით")) continue
            if (view is TextView && view.text.toString() in groupTitles) {
                section = newCard()
                view.textSize=19f
                view.typeface=Typeface.DEFAULT_BOLD
                view.setTextColor(Booth360Ui.WHITE)
            }
            if (section == null) section = newCard()
            if (view is Button) {
                view.isAllCaps=false
                view.textSize=14f
                view.typeface=Typeface.DEFAULT_BOLD
                view.setTextColor(Booth360Ui.WHITE)
                view.background=roundBackground(0xff213B61.toInt(),0xff3D5C80.toInt(),13)
                view.minimumHeight=dp(48)
                view.minHeight=dp(48)
            }
            if (view is TextView && view !is Button) {
                if (view.textSize/resources.displayMetrics.scaledDensity < 17f) {
                    view.setTextColor(0xffAFC2D9.toInt())
                } else view.setTextColor(Booth360Ui.WHITE)
            }
            if (view is EditText) {
                view.setTextColor(Color.WHITE)
                view.setHintTextColor(Booth360Ui.MUTED)
                view.background=roundBackground(0xff172A46.toInt(),0xff314C72.toInt(),12)
                view.setPadding(dp(13),dp(13),dp(13),dp(13))
            }
            if (view is SeekBar) {
                if (Build.VERSION.SDK_INT >= 21) {
                    view.progressTintList=ColorStateList.valueOf(Booth360Ui.AQUA)
                    view.thumbTintList=ColorStateList.valueOf(Booth360Ui.WHITE)
                }
            }
            if (view is RadioGroup) {
                for (i in 0 until view.childCount) {
                    (view.getChildAt(i) as? RadioButton)?.apply {
                        setTextColor(Booth360Ui.WHITE)
                        if (Build.VERSION.SDK_INT >= 21) buttonTintList=ColorStateList.valueOf(Booth360Ui.AQUA)
                    }
                }
            }
            section!!.addView(view,LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(5)})
        }
    }

    private fun showWifiCredentialsDialog(retry:Boolean){
        val current=importer.saved()
        val fields=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(dp(18),dp(8),dp(18),0)
        }
        val ssid=EditText(this).apply{
            hint="GoPro Wi-Fi სახელი (SSID)"
            setSingleLine(true)
            setText(current?.ssid?:"")
            fields.addView(this)
        }
        val password=EditText(this).apply{
            hint="GoPro Wi-Fi პაროლი"
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(current?.password?:"")
            fields.addView(this)
        }
        AlertDialog.Builder(this)
            .setTitle("GoPro HERO9 Wi-Fi")
            .setMessage("ერთჯერადად შეიყვანე კამერის Connections → Camera Info-ში მითითებული Wi-Fi სახელი და პაროლი. შემდეგ აპლიკაცია შეეცდება ავტომატურად დაკავშირებას. Bluetooth-ით მიღებული მონაცემები ავტომატურად შეინახება.")
            .setView(fields)
            .setNegativeButton("გაუქმება",null)
            .setPositiveButton("შენახვა"){_,_->
                runCatching{
                    importer.save(ssid.text.toString(),password.text.toString())
                }.onSuccess{
                    importStatus.text="Wi-Fi სახელი/პაროლი შენახულია. დასაკავშირებლად დააჭირე «⟳ GoPro-დან ბოლო MP4-ის გადმოტანა» ან გადაიღე ახალი ვიდეო."
                    Toast.makeText(this,"მხოლოდ Wi-Fi მონაცემები შეინახა; ვიდეო ჯერ არ გადმოწერილა",Toast.LENGTH_LONG).show()
                    if(retry)startGoProImport(true)
                }.onFailure{
                    importStatus.text=it.message?:"არასწორი Wi-Fi მონაცემები"
                    Toast.makeText(this,it.message,Toast.LENGTH_LONG).show()
                }
            }.show()
    }
    private fun wifiPermissionGranted():Boolean{
        val name=if(Build.VERSION.SDK_INT>=33)
            Manifest.permission.NEARBY_WIFI_DEVICES
            else Manifest.permission.ACCESS_FINE_LOCATION
        if(Build.VERSION.SDK_INT>=29 && checkSelfPermission(name)!=PackageManager.PERMISSION_GRANTED){
            pendingWifiImport=true
            requestPermissions(arrayOf(name),811)
            return false
        }
        return true
    }
    private fun startGoProImport(automatic:Boolean){
        if(importingFromGoPro||exporting||recording||busy){
            importStatus.text="ჯერ მიმდინარე გადაღება ან ვიდეოს დამუშავება დაასრულე"
            return
        }
        if(!wifiPermissionGranted())return
        importingFromGoPro=true;busy=true;refresh()
        importStatus.text="GoPro-სთან ვამყარებ Wi-Fi კავშირს…"
        Toast.makeText(this,"GoPro-ს Wi-Fi კავშირს ვამოწმებ. თუ Android კავშირის დადასტურებას მოითხოვს, აირჩიე Connect.",Toast.LENGTH_LONG).show()
        val beginDownload:()->Unit = {
            val stored=importer.saved()
            if(stored==null){
                importingFromGoPro=false;busy=false;refresh()
                importStatus.text="GoPro Wi-Fi მონაცემები მიუთითე"
                showWifiCredentialsDialog(automatic)
            }else{
                importer.downloadLatest(if(automatic)captureStartedAtMs else 0L,
                    {message->importStatus.text=message;state.text=message;android.util.Log.d("MOMENTO-Import",message)},
                    {result->
                        importingFromGoPro=false;busy=false
                        result.onSuccess{file->
                            videoInput=Uri.fromFile(file)
                            selectedPlan=selectedPlan?:plan()
                            outputFile=null
                            mediaLabel.text="GoPro-დან გადმოტანილია: "+file.name+". იწყება MP4 დამუშავება…"
                            importStatus.text="გადმოტანილია • "+file.name
                            refresh()
                            exportSelected()
                        }.onFailure{error->
                            importStatus.text="GoPro გადმოტანა ვერ შესრულდა: "+error.message
                            state.text="კამერის ორიგინალი microSD-ზე უსაფრთხოდაა შენახული"
                            refresh()
                            AlertDialog.Builder(this).setTitle("GoPro-ს ვიდეო არ გადმოწერილა")
                                .setMessage((error.message?:"უცნობი შეცდომა")+
                                    "\n\nრა შეამოწმო:\n1. GoPro ჩართულია და SD ბარათზე ვიდეო ჩანს.\n2. GoPro-ს Bluetooth კავშირი READY არის.\n3. ტელეფონზე გამოჩენილ GoPro Wi-Fi დაკავშირების მოთხოვნას დააჭირე Connect.\n4. თუ Wi-Fi ავტომატურად ვერ ერთდება, დროებით ხელით დაუკავშირდი GoPro-ს Wi-Fi ქსელს Android Settings-იდან და ისევ სცადე.\n5. Wi-Fi-ს «No internet» შეტყობინება GoPro-სთვის ნორმალურია.")
                                .setNegativeButton("დახურვა",null)
                                .setPositiveButton("Wi-Fi პარამეტრები"){_,_->
                                    startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
                                }.show()
                        }
                    })
            }
        }
        if(connected){
            ble.enableWifiAp{ok->runOnUiThread{
                if(!ok){
                    // An AP enable response may time out even if the camera Wi-Fi is already active.
                    importStatus.text="Wi-Fi ბრძანების პასუხი ვერ მივიღეთ — პირდაპირ ვცდით დაკავშირებას…"
                    beginDownload()
                }else{
                    importStatus.text="GoPro Wi-Fi აქტიურდება…"
                    ble.readWifiCredentials{ssid,password->runOnUiThread{
                        if(!ssid.isNullOrBlank()&&!password.isNullOrBlank())
                            runCatching{importer.save(ssid,password)}
                        handler.postDelayed({beginDownload()},1800)
                    }}
                }
            }}
        }else{
            // A manually-connected Wi-Fi network can be used without BLE.
            beginDownload()
        }
    }

    private fun chooseBoothTime() {
        val input=EditText(this).apply {
            inputType=InputType.TYPE_CLASS_NUMBER
            setText("10")
            setPadding(dp(18),dp(12),dp(18),dp(12))
        }
        AlertDialog.Builder(this).setTitle("დროით გაშვება")
            .setMessage("რამდენი წამით იმუშაოს პლატფორმამ?")
            .setView(input)
            .setNegativeButton("გაუქმება",null)
            .setPositiveButton("START") { _, _ ->
                val sec=input.text.toString().trim().toIntOrNull()
                if(sec==null||sec !in 1..60) boothUi.showMessage("დრო უნდა იყოს 1–60 წამი")
                else startSynchronized360(sec)
            }.show()
    }

    private fun startSynchronized360(seconds: Int) {
        if (pending360 || syncing360 || tok.motorOn==true) {
            boothUi.showMessage("პლატფორმა უკვე მუშაობს")
            return
        }
        if (!tok.connected) {boothUi.showMessage("ჯერ დაუკავშირდი 360Tok-ს");return}
        // Drive uploads run on their own worker thread and use immutable queued files.
        // A previous guest's upload must NEVER block the next shutter/motor START.
        // Only GoPro capture, import and video export are mutually exclusive here.
        if (busy || exporting || reservingLink || recording || importingFromGoPro) {
            boothUi.showMessage("ჯერ დაასრულე მიმდინარე გადაღება ან MP4-ის დამუშავება")
            return
        }
        // The existing GoPro v0.7 uses a separate Wi-Fi preview capture in SD-less mode.
        // Synchronized shutter requires microSD; do not claim SD-less streaming is recording.
        val useCamera=boothUi.isCameraSyncOn()
        if (useCamera && storageSelector.checkedRadioButtonId != 1001) {
            AlertDialog.Builder(this).setTitle("GoPro სინქრონიზაცია")
                .setMessage("360° სინქრონიზებული ჩაწერისთვის GoPro ტაბში აირჩიე microSD რეჟიმი. Wi-Fi Preview ცალკე მუშაობს GoPro ტაბში.")
                .setPositiveButton("GoPro", {_,_->showTab(false)})
                .setNegativeButton("გაუქმება",null).show()
            return
        }
        if (useCamera && !connected) {
            AlertDialog.Builder(this).setTitle("GoPro არ არის დაკავშირებული")
                .setMessage("გადადით GoPro ტაბში და დააკავშირე HERO9; ან გამორთე GoPro სინქრონიზაცია 360 ტაბში მხოლოდ პლატფორმის სამართავად.")
                .setPositiveButton("GoPro",{_,_->showTab(false)})
                .setNegativeButton("გაუქმება",null).show()
            return
        }
        if(useCamera){
            session=null;api.clearSession()
            outputFile=null;api.clearExportedFile()
            videoInput=null
            selectedPlan=plan()
            captureStartedAtMs=System.currentTimeMillis()
        }
        val generation=++syncSessionId
        current360Seconds=seconds
        motorEverStarted=false
        pending360=true
        boothUi.setPending(true)
        if (useCamera) {
            busy=true;refresh()
            ble.shutter(true) { ok -> runOnUiThread {
                if (generation!=syncSessionId) {
                    // STOP was pressed before the camera responded. Prevent orphaned recording.
                    if (ok) ble.shutter(false) { }
                    return@runOnUiThread
                }
                busy=false
                pending360=false
                boothUi.setPending(false)
                if (!ok) {
                    boothUi.showStopped()
                    boothUi.showMessage("GoPro START ვერ დადასტურდა; პლატფორმა არ დატრიალებულა")
                    refresh()
                    return@runOnUiThread
                }
                syncing360=true
                goProCaptureActive=true
                recording=true
                boothUi.showStarted(seconds)
                tok.start(seconds)
                schedule360Stop(seconds)
                refresh()
            } }
        } else {
            pending360=false
            syncing360=true
            boothUi.showStarted(seconds)
            tok.start(seconds)
            schedule360Stop(seconds)
        }
    }

    private fun schedule360Stop(seconds:Int) {
        syncStopTimeout?.let{handler.removeCallbacks(it)}
        motorOnlyTimeout?.let{handler.removeCallbacks(it)}
        if (seconds<=0)return
        val generation=syncSessionId
        val task=Runnable {
            if (generation==syncSessionId && syncing360) {
                // Controller is responsible for timed stop; also send STOP as a failsafe.
                stopSynchronized360()
            }
        }
        syncStopTimeout=task
        handler.postDelayed(task,seconds*1000L+600)
    }

    private fun stopSynchronized360(motorAlreadyStopped:Boolean=false) {
        ++syncSessionId
        syncStopTimeout?.let{handler.removeCallbacks(it)};syncStopTimeout=null
        motorOnlyTimeout?.let{handler.removeCallbacks(it)};motorOnlyTimeout=null
        if (!motorAlreadyStopped && tok.connected) tok.stop()
        pending360=false
        val wasRecording=goProCaptureActive
        goProCaptureActive=false
        syncing360=false
        motorEverStarted=false
        boothUi.showStopped()
        if (wasRecording) {
            busy=true;refresh()
            ble.shutter(false) { ok -> runOnUiThread {
                busy=false
                recording=!ok
                if (ok) {
                    state.text="GoPro გადაღება დასრულდა. იწყება microSD-დან გადმოტანა…"
                    startGoProImport(true)
                } else state.text="GoPro STOP ვერ დადასტურდა — გადაამოწმე კამერა"
                refresh()
            } }
        } else if (recording && !wasRecording) {
            // Existing stand-alone GoPro tab capture is not affected.
        }
    }

    /** Queue the processed MP4 before the network-dependent session reservation. */
    private fun queuePrepared(file:File) {
        try {
            preparedVideos.add(file)
            linkStatus.text = "დამუშავებული MP4 შენახულია. სერვერზე გადატანის მოლოდინში…"
            updatePendingLabel()
            autoProcessQueues()
        } catch(e:Exception) {
            linkStatus.text = "MP4 არსებობს, მაგრამ რიგში ვერ შეინახა: ${e.message}"
        }
    }

    /** Continue after an offline GoPro session without losing earlier guests' videos. */
    private fun autoProcessQueues() {
        if (!::preparedVideos.isInitialized || !::pendingUploads.isInitialized ||
            uploading || reservingLink || reservingPrepared || recording || exporting ||
            importingFromGoPro || busy || isFinishing) return
        if (pendingUploads.size()>0) {
            uploadNextPending()
            return
        }
        val file=preparedVideos.all().firstOrNull() ?: return
        if (!api.hasKey()) {
            linkStatus.text="ვიდეო დაცულია ტელეფონში. Google Drive-ისთვის შეიყვანე WordPress 360 API გასაღები."
            return
        }
        reservingPrepared=true
        linkStatus.text="მზად MP4-ისთვის ვქმნი ბმულს: ${file.name}"
        api.reserve { outcome ->
            reservingPrepared=false
            outcome.onSuccess { s ->
                try {
                    pendingUploads.add(s,file)
                    preparedVideos.remove(file)
                    if (outputFile?.absolutePath == file.absolutePath) {
                        session=s
                        api.saveSession(s)
                        linkStatus.text="ვიდეოს ბმული: ${s.url}. ახლა იტვირთება."
                    }
                    updatePendingLabel()
                    uploadNextPending()
                } catch(e:Exception) {
                    linkStatus.text="ვიდეო შენახულია, მაგრამ ატვირთვის რიგის შეცდომაა: ${e.message}"
                }
            }.onFailure { e ->
                linkStatus.text="ვიდეო ტელეფონში დაცულია; ბმული ჯერ ვერ შეიქმნა: ${e.message}. კავშირის აღდგენისას ავტომატურად განმეორდება."
                updatePendingLabel()
            }
        }
    }

    private fun reserveLink(autoUpload: Boolean = false) {
        if (reservingLink) {
            uploadAfterReserve = uploadAfterReserve || autoUpload
            return
        }
        if (session != null) {
            if (autoUpload) uploadReadyFile() else openShareDialog()
            return
        }
        if (!api.hasKey()) {
            Toast.makeText(this, "ჯერ 360 API გასაღები შეინახე", Toast.LENGTH_LONG).show()
            return
        }
        linkStatus.text = "სერვერზე უნიკალური ბმულის შექმნა…"
        reservingLink = true
        uploadAfterReserve = autoUpload
        api.reserve { outcome ->
            reservingLink = false
            val shouldUpload = uploadAfterReserve
            uploadAfterReserve = false
            outcome.onSuccess { s ->
                session = s
                api.saveSession(s)
                linkStatus.text = "ვიდეოს ბმული: ${s.url}\nატვირთვის დასრულებამდე სტუმარი იხილავს მოლოდინის გვერდს."
                refresh()
                if (shouldUpload) uploadReadyFile() else openShareDialog()
            }.onFailure { e ->
                linkStatus.text = "ბმული ვერ შეიქმნა: ${e.message}. გადაამოწმე ინტერნეტი, API გასაღები და WordPress."
                refresh()
            }
        }
    }

    /** Persist the completed video before any network request. */
    private fun uploadReadyFile() {
        val s = session ?: run { Toast.makeText(this,"ჯერ შექმენი ბმული",Toast.LENGTH_SHORT).show(); return }
        val file = outputFile ?: run { Toast.makeText(this,"ჯერ დაამუშავე და შექმენი MP4",Toast.LENGTH_SHORT).show(); return }
        try {
            pendingUploads.add(s, file)
            updatePendingLabel()
            uploadNextPending()
        } catch (e: Exception) {
            linkStatus.text = "ატვირთვის რიგში დამატება ვერ მოხერხდა: ${e.message}"
        }
    }

    /** Sequential, resumable uploads. A failure leaves the job saved until retry. */
    private fun uploadNextPending() {
        if (uploading || exporting || recording) return
        val job = pendingUploads.all().firstOrNull()
        if (job == null) {
            updatePendingLabel()
            Toast.makeText(this, "გადაუტვირთავი ვიდეოები არ არის", Toast.LENGTH_SHORT).show()
            return
        }
        uploading = true
        refresh()
        if (session?.token == job.session.token)
            linkStatus.text = "იწყება შენახული ვიდეოს ატვირთვა…\n${job.session.url}"
        api.upload(job.file, job.session, { progress ->
            if (session?.token == job.session.token)
                linkStatus.text = "$progress\n${job.session.url}"
        }, { result ->
            uploading = false
            result.onSuccess { url ->
                pendingUploads.remove(job.session.token)
                if (session?.token == job.session.token)
                    linkStatus.text = "ვიდეო ატვირთულია და ხელმისაწვდომია! 💖\n$url"
                updatePendingLabel()
                handler.post { autoProcessQueues() }
            }.onFailure { e ->
                if (session?.token == job.session.token)
                    linkStatus.text = "ატვირთვა ვერ დასრულდა: ${e.message}\nვიდეო შენახულია რიგში და ავტომატურად განმეორდება, როცა ინტერნეტი ხელმისაწვდომი იქნება."
                updatePendingLabel()
            }
            refresh()
        })
    }

    private fun updatePendingLabel() {
        if (::pendingLabel.isInitialized)
            pendingLabel.text = (if (uploading) "☁ Google Drive-ზე ატვირთვა მიმდინარეობს ფონურად — ახალი გადაღება შეგიძლია!\n" else "") +
                "ატვირთვის მოლოდინში: ${pendingUploads.size()} ვიდეო; ბმულის მოლოდინში: ${preparedVideos.size()} ვიდეო. არ წაშალო აპლიკაცია."
    }

    private fun openShareDialog() {
        if (session == null) { reserveLink(); return }
        AlertDialog.Builder(this).setTitle("როგორ გააზიარებთ ვიდეოს?")
            .setItems(arrayOf("🔗 ლინკი", "▦ QR კოდი", "✉ SMS")) { _, index ->
                when(index) { 0 -> showLink(); 1 -> showQr(); 2 -> showSms() }
            }.setNegativeButton("დახურვა",null).show()
    }

    private fun showLink() {
        val link=session?.url ?: return
        AlertDialog.Builder(this).setTitle("ვიდეოს ლინკი").setMessage(link)
            .setPositiveButton("📋 დაკოპირება") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("MOMENTO ვიდეო",link))
                Toast.makeText(this,"ლინკი დაკოპირებულია",Toast.LENGTH_SHORT).show()
            }.setNegativeButton("დახურვა",null).show()
    }

    private fun showQr() {
        val link=session?.url ?: return
        runCatching {
            val view=ImageView(this).apply { setImageBitmap(QrRender.encode(link)); adjustViewBounds=true; setPadding(24,24,24,24) }
            AlertDialog.Builder(this).setTitle("დაასკანერეთ QR კოდი 💖")
                .setView(view).setPositiveButton("დახურვა",null).show()
        }.onFailure { Toast.makeText(this,"QR-ის შექმნა ვერ მოხერხდა: ${it.message}",Toast.LENGTH_LONG).show() }
    }

    private fun showSms() {
        val s=session ?: return
        val form=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(20,0,20,0) }
        val name=EditText(this).apply { hint="სტუმრის სახელი"; inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS; form.addView(this) }
        val phone=EditText(this).apply { hint="5XXXXXXXX"; inputType=InputType.TYPE_CLASS_PHONE; form.addView(this) }
        TextView(this).apply { text="თქვენი გადაღებული ვიდეოს სანახავად, გადადით ლინკზე.\n[ვიდეოს ბმული]\nმოგონებით: MOMENTO 💖."; setPadding(12,16,12,0); form.addView(this) }
        val dialog=AlertDialog.Builder(this).setTitle("SMS — MOMENTO 360").setView(form)
            .setPositiveButton("SMS გაგზავნა",null).setNegativeButton("გაუქმება",null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val normalized=phone.text.toString().replace(Regex("[^0-9]"), "")
                if (!Regex("(?:995)?5[0-9]{8}").matches(normalized)) { phone.error="მიუთითე საქართველოს მობილურის ნომერი"; return@setOnClickListener }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=false
                api.sendSms(s,name.text.toString(),normalized){ result ->
                    result.onSuccess { if(it) { Toast.makeText(this,"SMS მიღებულია SMSOffice-ის მიერ",Toast.LENGTH_LONG).show(); dialog.dismiss() } else {
                        Toast.makeText(this,"SMS არ დადასტურდა",Toast.LENGTH_LONG).show();dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=true
                    } }.onFailure { e ->
                        Toast.makeText(this,"SMS შეცდომა: ${e.message}",Toast.LENGTH_LONG).show()
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=true
                    }
                }
            }
        }
        dialog.show()
    }

    private fun plan(): RecordingPlan {
        val total = time.progress + 5
        val from = slowFrom.progress.coerceAtMost(total - 1)
        val slow = (slowLength.progress + 1).coerceAtMost(total - from)
        val multiplier = when (speed.selectedItemPosition) { 0 -> .5; 2 -> .125; else -> .25 }
        return RecordingPlan(total, from, slow, multiplier)
    }
    private fun refresh() {
        if (!::time.isInitialized || !::speed.isInitialized || !::start.isInitialized) return
        val total = time.progress + 5
        slowFrom.max = total - 1
        if (slowFrom.progress > total - 1) slowFrom.progress = total - 1
        slowLength.max = total - slowFrom.progress - 1
        if (slowLength.progress > slowLength.max) slowLength.progress = slowLength.max
        val p = plan()
        timeLabel.text = "${p.finalSeconds} წამი"
        fromLabel.text = "${p.slowStartSeconds}-ე წამიდან"
        lengthLabel.text = "${p.slowSeconds} წამი"
        planView.text = "მზა ვიდეო: ${p.slowStartSeconds} წმ ჩვეულებრივ + ${p.slowSeconds} წმ შენელებული + ${p.normalAfterSeconds} წმ ჩვეულებრივ = ${p.finalSeconds} წმ\nკამერის რეალური გადაღება: ${String.format(Locale.US, "%.3f", p.realCaptureSeconds)} წმ (თეორიული)"
        val sdMode = !::storageSelector.isInitialized || storageSelector.checkedRadioButtonId == 1001
        start.isEnabled = (connected || !sdMode || recording) && !busy && !exporting
        if (::exportButton.isInitialized) exportButton.isEnabled = videoInput != null && !exporting
        if (::saveButton.isInitialized) saveButton.isEnabled = outputFile != null && !exporting
        if (::shareButton.isInitialized) shareButton.isEnabled = session != null
        if (::uploadButton.isInitialized) uploadButton.isEnabled = session != null && outputFile != null && !uploading
        updatePendingLabel()
        start.text = if (recording) "STOP" else "START - გადაღება"
        for (bar in listOf(time, slowFrom, slowLength)) bar.isEnabled = !recording && !busy
        speed.isEnabled = !recording && !busy
        if (::storageSelector.isInitialized) { for(i in 0 until storageSelector.childCount) storageSelector.getChildAt(i).isEnabled = !recording && !busy }
    }
    private fun startRecording() {
        if (busy || recording || syncing360 || pending360) return
        if (storageSelector.checkedRadioButtonId == 1002) {
            startWithoutSd()
            return
        }
        if (!connected) { state.text = "ჯერ Bluetooth-ით დაუკავშირდი GoPro-ს"; return }
        if (exporting) {
            state.text = "ჯერ მიმდინარე ვიდეოს დამუშავება დაასრულე."
            return
        }
        session = null; api.clearSession()
        outputFile = null; api.clearExportedFile()
        videoInput = null
        mediaLabel.text = "ახალი სტუმრისთვის აირჩიე ახლად გადაღებული ვიდეო."
        linkStatus.text = "ახალი სტუმარი: ბმული შეიქმნება გადაღების დასრულებისას."
        val p = plan(); selectedPlan = p
        captureStartedAtMs=System.currentTimeMillis()
        busy = true; refresh()
        state.text = "კამერისთვის START ბრძანების გაგზავნა…"
        ble.shutter(true) { ok -> runOnUiThread {
            busy = false
            if (!ok) { state.text = "START ვერ დადასტურდა"; refresh(); return@runOnUiThread }
            recording = true
            val millis = (p.realCaptureSeconds * 1000).toLong()
            val task = Runnable { stopRecording() }
            stoppingTask = task
            handler.postDelayed(task, millis)
            state.text = "მიმდინარეობს გადაღება; ავტომატურად შეჩერდება ~${String.format(Locale.US,"%.2f",p.realCaptureSeconds)} წმ-ში"
            refresh()
        } }
    }
    private fun startWithoutSd() {
        if (exporting || busy) { state.text = "მიმდინარე დამუშავება ჯერ არ დასრულებულა"; return }
        session = null; api.clearSession()
        outputFile = null; api.clearExportedFile()
        videoInput = null
        linkStatus.text = "ახალი სტუმარი: ბმული შეიქმნება გადაღების დასრულებისას."
        val p = plan(); selectedPlan = p
        getSharedPreferences("momento_preview", MODE_PRIVATE).edit()
            .putInt("total", p.finalSeconds).putInt("from", p.slowStartSeconds)
            .putInt("slow", p.slowSeconds)
            .putLong("speed", java.lang.Double.doubleToLongBits(p.speed)).apply()
        mediaLabel.text = "GoPro Wi-Fi Preview-ის მიღებას ველოდებით…"
        busy = true
        refresh()
        // Add a short guard margin to account for MPEG-TS frames and 30fps timestamps.
        // The VideoExporter will clip to the exact theoretical source duration.
        previewRecorder.start(p.realCaptureSeconds + 0.65, { message -> state.text = message }, {
            busy = false
            recording = true
            refresh()
        }, { result ->
            busy = false
            recording = false
            result.onSuccess { file ->
                lastPreviewFile = file
                videoInput = Uri.fromFile(file)
                mediaLabel.text = "კამერის Wi-Fi ნაკადი ჩაიწერა: ${file.name}. იწყება MP4 დამუშავება…"
                refresh()
                reserveLink()
                exportSelected()
            }.onFailure { e ->
                mediaLabel.text = "ვიდეო არ ჩაიწერა. ${e.message}"
                state.text = "SD-ის გარეშე რეჟიმი ვერ შესრულდა. გადამოწმე Wi-Fi ან კამერის მხარდაჭერა."
                refresh()
                AlertDialog.Builder(this).setTitle("GoPro Wi-Fi Preview ვერ ჩაიწერა")
                    .setMessage("${e.message}\n\nეს კონკრეტულ HERO9-ზე შესაძლოა SD ბარათის გარეშე საერთოდ არ იყოს მხარდაჭერილი. ჩაწერილად ვერ ჩაითვლება, სანამ ტელეფონში რეალური ვიდეოფაილი არ მივიღეთ.")
                    .setPositiveButton("გასაგებია", null).show()
            }
        })
    }

    private fun stopRecording() {
        if (syncing360 || pending360) { stopSynchronized360(); return }
        if (storageSelector.checkedRadioButtonId == 1002 && previewRecorder.isActive()) {
            state.text = "GoPro Wi-Fi ნაკადის შეჩერება…"
            busy = true
            previewRecorder.stop()
            refresh()
            return
        }
        stoppingTask?.let { handler.removeCallbacks(it) }; stoppingTask = null
        if (!recording || busy) return
        busy = true; refresh()
        ble.shutter(false) { ok -> runOnUiThread {
            busy = false
            if (ok) recording = false
            state.text = if (ok) "გადაღება დასრულდა. ფაილი GoPro-ის SD ბარათზეა." else "STOP ვერ დადასტურდა — გადაამოწმე კამერა და სცადე STOP ხელახლა"
            refresh()
            if (ok) startGoProImport(true)
        } }
    }
    private fun exportSelected() {
        val uri = videoInput ?: return
        if (exporting) return
        exporting = true
        outputFile = null
        refresh()
        val p = selectedPlan ?: plan()
        val notify: (String) -> Unit = { message -> runOnUiThread { mediaLabel.text = message } }
        val finished: (Result<File>) -> Unit = { result ->
            runOnUiThread {
                exporting = false
                result.onSuccess { file ->
                    outputFile = file
                    api.saveExportedFile(file)
                    mediaLabel.text = "Slow Motion MP4 მზადაა: ${file.name}. ინახება გალერეაში…"
                    GalleryPublisher.publish(this@MainActivity,file) { saveResult ->
                        if(outputFile?.absolutePath == file.absolutePath) {
                            saveResult.onSuccess{ location ->
                                mediaLabel.text="Slow Motion MP4 შენახულია: $location"
                            }.onFailure{ e ->
                                mediaLabel.text="MP4 დამუშავებულია და აპში ინახება, მაგრამ გალერეაში ვერ შეინახა: ${e.message}"
                            }
                        }
                    }
                    queuePrepared(file)
                }
                    .onFailure { e -> mediaLabel.text = "MP4 ვერ შეიქმნა: ${e.message}\nორიგინალი Preview არ წაშლილა — გამოიყენე მისი შენახვის ღილაკი." }
                refresh()
            }
        }
        if (uri.scheme == "file" && uri.path?.endsWith(".ts", ignoreCase = true) == true) {
            // Preview MPEG-TS is remuxed directly, avoiding indefinite Media3 TS export hangs.
            previewMp4Exporter.export(File(requireNotNull(uri.path)), p, notify, finished)
        } else {
            exporter.export(uri, p, notify, finished)
        }
    }

    @Deprecated("Android activity result compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            901 -> {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                videoInput = uri
                selectedPlan = plan()
                outputFile = null
                mediaLabel.text = "ვიდეო არჩეულია. მზად არის ექსპორტისთვის."
                refresh()
            }
            903 -> {
                val file = lastPreviewFile ?: return
                try {
                    contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                        ?: error("საქაღალდეში შენახვა ვერ მოხერხდა")
                    mediaLabel.text = "ორიგინალი Preview TS შენახულია: ${file.name}"
                } catch (e: Exception) { mediaLabel.text = "Preview შენახვის შეცდომა: ${e.message}" }
            }
            902 -> {
                val file = outputFile ?: return
                try {
                    contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                        ?: error("ფაილის გახსნა ვერ მოხერხდა")
                    mediaLabel.text = "MP4 წარმატებით შენახულია!"
                } catch (e: Exception) { mediaLabel.text = "შენახვის შეცდომა: ${e.message}" }
            }
        }
    }

    private fun permissions(): Boolean {
        val wanted = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) { requestPermissions(missing.toTypedArray(), 800); return false }
        return true
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 800 && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            if (boothScroll.visibility == View.VISIBLE) tok.connect() else ble.scanAndConnect()
        }
        if (requestCode == 811 && pendingWifiImport) {
            pendingWifiImport=false
            if(grantResults.isNotEmpty()&&grantResults.all{it==PackageManager.PERMISSION_GRANTED})
                startGoProImport(true)
            else if(::importStatus.isInitialized)
                importStatus.text="GoPro Wi-Fi-სთან ავტომატური დაკავშირების ნებართვა აუცილებელია"
        }
    }
    override fun onResume() {
        super.onResume()
        if (::preparedVideos.isInitialized) {
            retryLoop?.let{handler.removeCallbacks(it)}
            val task=object:Runnable {
                override fun run(){
                    if(isFinishing || isDestroyed)return
                    autoProcessQueues()
                    handler.postDelayed(this,20000L)
                }
            }
            retryLoop=task
            handler.postDelayed(task,1500L)
        }
    }

    override fun onPause() {
        retryLoop?.let{handler.removeCallbacks(it)}
        retryLoop=null
        super.onPause()
    }

    override fun onDestroy() {
        retryLoop?.let{handler.removeCallbacks(it)}
        stoppingTask?.let { handler.removeCallbacks(it) }
        // Warning: foreground capture is intentionally not supported in v0.1.
        exporter.cancel()
        if (::previewMp4Exporter.isInitialized) previewMp4Exporter.cancel()
        if (::previewRecorder.isInitialized) previewRecorder.stop()
        if (::importer.isInitialized) importer.close()
        syncStopTimeout?.let { handler.removeCallbacks(it) }
        motorOnlyTimeout?.let { handler.removeCallbacks(it) }
        // Prevent camera recording from being orphaned by a tab / process shutdown.
        if (syncing360 || pending360) stopSynchronized360()
        if (::tok.isInitialized) tok.disconnect()
        ble.disconnect(); super.onDestroy()
    }
}
