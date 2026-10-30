package ge.momento.booth

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/** A premium dark single-button 360 control tab, entirely programmatic and dependency-free. */
class Booth360Ui(
    private val activity: Activity,
    private val onConnect: () -> Unit,
    private val onToggle: () -> Unit,
    private val onTimed: (Int) -> Unit,
    private val onCustom: () -> Unit,
    private val onDirection: (Int) -> Unit,
    private val onSpeed: (Int) -> Unit,
    private val onLight: (Boolean) -> Unit,
    private val onMode: (Int) -> Unit,
    private val onBrightness: (Int) -> Unit,
    private val onCameraTab: () -> Unit
) {
    companion object {
        const val BG = 0xff091222.toInt()
        const val CARD = 0xff101E36.toInt()
        const val CARD_EDGE = 0xff253E61.toInt()
        const val WHITE = 0xffF6F9FF.toInt()
        const val MUTED = 0xff9DB3CE.toInt()
        const val AQUA = 0xff47EAE1.toInt()
        const val RED = 0xffFF427F.toInt()
        const val BLUE = 0xff155BE5.toInt()
    }
    val screen: ScrollView = ScrollView(activity).apply {
        setBackgroundColor(BG)
        isVerticalScrollBarEnabled = false
        isFillViewport = true
    }
    private val root = LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(15),dp(18),dp(15),dp(20)) }
    private val motorButton = RingMotorButton()
    private lateinit var motorBadge: TextView
    private lateinit var motorConnection: TextView
    private lateinit var cameraConnection: TextView
    private lateinit var speedValue: TextView
    private lateinit var directionNormal: Button
    private lateinit var directionReverse: Button
    private lateinit var lightOn: Button
    private lateinit var lightOff: Button
    private lateinit var modes: List<Button>
    private lateinit var cameraSync: Switch
    private val timers = linkedMapOf<Int,Button>()
    private lateinit var customTimer: Button
    private var motorReady=false
    private var cameraReady=false
    private var motorRunning=false
    private var motorUnknown=true
    private var currentSpeed=6
    private var currentDirection=0
    private var lightEnabled=true
    private var currentMode=0
    private var currentTimer=0
    private var working=false

    init {
        screen.addView(root)
        val brandRow = horizontal()
        brandRow.gravity=Gravity.CENTER_VERTICAL
        root.addView(brandRow,params(-1,56))
        val mark = label("M",23f,WHITE,true).apply {
            gravity=Gravity.CENTER
            background=gradient(intArrayOf(0xff1FE0CA.toInt(),0xff2574EE.toInt(),0xff5D47D4.toInt()),15)
        }
        brandRow.addView(mark,params(51,51))
        val brandText=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL}
        val brandParams=LinearLayout.LayoutParams(0,-2,1f).apply { leftMargin=dp(11) }
        brandRow.addView(brandText,brandParams)
        brandText.addView(label("MOMENTO 360",20f,WHITE,true))
        brandText.addView(label("PHOTO BOOTH CONTROL",10f,MUTED,false))
        val statusPill=label("BLE",11f,AQUA,true).apply {
            gravity=Gravity.CENTER; background=outlined(0xff102B41.toInt(),0xff27516B.toInt(),18)
        }
        brandRow.addView(statusPill,params(47,32))

        val connects=panel()
        val badges=horizontal().apply {gravity=Gravity.CENTER_VERTICAL}
        connects.addView(badges,params(-1,46))
        motorConnection=chip("●  360 OFFLINE")
        badges.addView(motorConnection,LinearLayout.LayoutParams(0,dp(42),1f))
        cameraConnection=chip("●  GoPro OFFLINE")
        badges.addView(cameraConnection,LinearLayout.LayoutParams(0,dp(42),1f).apply {leftMargin=dp(9)})
        motorConnection.setOnClickListener { onConnect() }
        cameraConnection.setOnClickListener { onCameraTab() }

        val main=panel()
        main.addView(label("◉  ბრუნვის მართვა",17f,WHITE,true))
        val circleParams=params(186,186,5).apply {gravity=Gravity.CENTER_HORIZONTAL}
        main.addView(motorButton,circleParams)
        motorButton.setOnClickListener { if(!working) onToggle() }
        motorBadge=chip("●  გაჩერებულია")
        main.addView(motorBadge,params(172,34,0).apply {gravity=Gravity.CENTER_HORIZONTAL})
        main.addView(label("მიმართულება",12f,MUTED,true),params(-1,-2,13))
        val dir=horizontal()
        main.addView(dir,params(-1,47,8))
        directionNormal=action("↶  ნორმალური",true)
        directionReverse=action("↷  უკუ",false)
        dir.addView(directionNormal,LinearLayout.LayoutParams(0,dp(47),1f))
        dir.addView(directionReverse,LinearLayout.LayoutParams(0,dp(47),1f).apply {leftMargin=dp(8)})
        directionNormal.setOnClickListener {onDirection(0)}
        directionReverse.setOnClickListener {onDirection(1)}

        val speeds=panel()
        speeds.addView(label("◷  სიჩქარე",17f,WHITE,true))
        val speedRow=horizontal().apply{gravity=Gravity.CENTER_VERTICAL}
        speeds.addView(speedRow,params(-1,63,9))
        val minus=action("−",false).apply {textSize=29f}
        val plus=action("+",false).apply {textSize=29f}
        speedRow.addView(minus,params(68,60))
        speedValue=label("6",32f,AQUA,true).apply {gravity=Gravity.CENTER}
        speedRow.addView(speedValue,LinearLayout.LayoutParams(0,dp(60),1f))
        speedRow.addView(plus,params(68,60))
        minus.setOnClickListener {onSpeed((currentSpeed-1).coerceAtLeast(1))}
        plus.setOnClickListener {onSpeed((currentSpeed+1).coerceAtMost(6))}
        val presets=horizontal()
        speeds.addView(presets,params(-1,42,10))
        for(i in 1..6){
            val n=i
            val button=action("$i",i==6).apply {textSize=12f}
            presets.addView(button,LinearLayout.LayoutParams(0,dp(41),1f).apply{if(i>1)leftMargin=dp(6)})
            button.setOnClickListener {onSpeed(n)}
        }

        val timer=panel()
        timer.addView(label("◷  დროით გაშვება",17f,WHITE,true))
        val timedRow=horizontal()
        timer.addView(timedRow,params(-1,49,11))
        for((i,seconds) in listOf(5,10,15,20).withIndex()){
            val bt=action("${seconds} წმ",false).apply {textSize=12f}
            timedRow.addView(bt,LinearLayout.LayoutParams(0,dp(49),1f).apply{if(i>0)leftMargin=dp(6)})
            timers[seconds]=bt
            bt.setOnClickListener {onTimed(seconds)}
        }
        customTimer=action("✎  სხვა დრო",false)
        timer.addView(customTimer,params(-1,46,9))
        customTimer.setOnClickListener {onCustom()}

        val lighting=panel()
        val lightingTitle=horizontal().apply{gravity=Gravity.CENTER_VERTICAL}
        lighting.addView(lightingTitle,params(-1,43))
        lightingTitle.addView(label("✦  განათება",17f,WHITE,true),LinearLayout.LayoutParams(0,-2,1f))
        lightOn=action("☀ ON",true)
        lightOff=action("☾ OFF",false)
        lightingTitle.addView(lightOn,params(72,39))
        lightingTitle.addView(lightOff,params(74,39).apply{leftMargin=dp(5)})
        lightOn.setOnClickListener {onLight(true)}
        lightOff.setOnClickListener {onLight(false)}
        lighting.addView(label("რეჟიმი",12f,MUTED,true),params(-1,-2,12))
        val modeRow=horizontal()
        lighting.addView(modeRow,params(-1,47,7))
        modes=listOf(action("Colors",true),action("CCT",false),action("Loop",false))
        modes.forEachIndexed {index,button->
            modeRow.addView(button,LinearLayout.LayoutParams(0,dp(47),1f).apply{if(index>0)leftMargin=dp(8)})
            button.setOnClickListener {onMode(index)}
        }
        val brightHeading=horizontal().apply {gravity=Gravity.CENTER_VERTICAL}
        lighting.addView(brightHeading,params(-1,36,12))
        brightHeading.addView(label("☼  სიკაშკაშე",12f,MUTED,true),LinearLayout.LayoutParams(0,-2,1f))
        val percent=label("100%",12f,WHITE,true)
        brightHeading.addView(percent)
        val slider=SeekBar(activity).apply{
            max=254; progress=254
            if(Build.VERSION.SDK_INT>=21){
                progressTintList=ColorStateList.valueOf(AQUA)
                thumbTintList=ColorStateList.valueOf(WHITE)
            }
        }
        lighting.addView(slider,params(-1,36))
        slider.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
            override fun onProgressChanged(bar:SeekBar?,position:Int,fromUser:Boolean){
                percent.text="${Math.round((position+1)*100f/255)}%"
            }
            override fun onStartTrackingTouch(bar:SeekBar?){}
            override fun onStopTrackingTouch(bar:SeekBar?){if(bar!=null)onBrightness(bar.progress+1)}
        })
        val syncContainer=panel()
        val syncLine=horizontal().apply{gravity=Gravity.CENTER_VERTICAL}
        syncContainer.addView(syncLine,params(-1,50))
        val syncTitles=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL}
        syncLine.addView(syncTitles,LinearLayout.LayoutParams(0,-2,1f))
        syncTitles.addView(label("GoPro სინქრონიზაცია",15f,WHITE,true))
        syncTitles.addView(label("START → REC → ბრუნვა",11f,MUTED,false))
        cameraSync=Switch(activity).apply {isChecked=true;thumbTintList=ColorStateList.valueOf(AQUA)}
        syncLine.addView(cameraSync)
        render()
    }
    fun isCameraSyncOn(): Boolean = cameraSync.isChecked
    fun setMotorConnection(connected:Boolean){motorReady=connected;render()}
    fun setCameraConnection(connected:Boolean){cameraReady=connected;render()}
    fun setMotorState(running:Boolean?,speed:Int?){
        if(running!=null){motorUnknown=false;motorRunning=running; if(!running)currentTimer=0}
        if(speed!=null&&speed in 1..6)currentSpeed=speed
        render()
    }
    fun setPending(pending:Boolean){working=pending;render()}
    fun showStarted(seconds:Int){motorUnknown=false;motorRunning=true;currentTimer=seconds;working=false;render()}
    fun showStopped(){motorUnknown=false;motorRunning=false;currentTimer=0;working=false;render()}
    fun setSpeed(speed:Int){currentSpeed=speed.coerceIn(1,6);render()}
    fun setDirection(direction:Int){currentDirection=direction;render()}
    fun setLight(on:Boolean){lightEnabled=on;render()}
    fun setMode(mode:Int){currentMode=mode;render()}
    fun showMessage(message:String){android.widget.Toast.makeText(activity,message,android.widget.Toast.LENGTH_LONG).show()}
    private fun render(){
        motorConnection.text=if(motorReady)"●  360 ONLINE" else "●  360 OFFLINE"
        motorConnection.setTextColor(if(motorReady)AQUA else MUTED)
        cameraConnection.text=if(cameraReady)"●  GoPro ONLINE" else "●  GoPro OFFLINE"
        cameraConnection.setTextColor(if(cameraReady)AQUA else MUTED)
        motorBadge.text=when {
            !motorReady -> "●  გათიშულია"
            working -> "●  მზადდება…"
            motorRunning -> "●  მუშაობს"
            else -> "●  გაჩერებულია"
        }
        motorBadge.setTextColor(if(motorRunning)AQUA else MUTED)
        motorButton.running=motorRunning
        motorButton.alpha=if(motorReady)1f else 0.52f
        motorButton.contentDescription=if(motorRunning)"STOP — ძრავის გაჩერება" else "START — ძრავის გაშვება"
        speedValue.text=currentSpeed.toString()
        setActive(directionNormal,currentDirection==0)
        setActive(directionReverse,currentDirection==1)
        setActive(lightOn,lightEnabled)
        setActive(lightOff,!lightEnabled)
        modes.forEachIndexed {i,button->setActive(button,i==currentMode)}
        timers.forEach{(time,button)->setActive(button,time==currentTimer&&motorRunning)}
        setActive(customTimer,currentTimer>0&&currentTimer !in timers.keys&&motorRunning)
    }
    private fun chip(value:String):TextView = label(value,11f,MUTED,true).apply{
        gravity=Gravity.CENTER; background=outlined(0xff11253E.toInt(),0xff254763.toInt(),20)
    }
    private fun action(text:String,active:Boolean):Button = Button(activity).apply{
        this.text=text; isAllCaps=false; textSize=14f; typeface=Typeface.DEFAULT_BOLD
        setPadding(dp(3),0,dp(3),0);minHeight=0;minimumHeight=0
        setActive(this,active)
    }
    private fun setActive(button:Button,active:Boolean){
        button.setTextColor(if(active)WHITE else 0xffC8D7ED.toInt())
        button.background=if(active)gradient(intArrayOf(0xff009CCF.toInt(),0xff176DEB.toInt(),0xff5348D3.toInt()),13)
                          else outlined(0xff1B2C49.toInt(),0xff354A6A.toInt(),13)
    }
    private fun panel():LinearLayout = LinearLayout(activity).apply{
        orientation=LinearLayout.VERTICAL
        setPadding(dp(15),dp(14),dp(15),dp(15))
        background=gradient(intArrayOf(0xff152642.toInt(),0xff0C1A30.toInt(),0xff101B34.toInt()),22).apply{
            setStroke(dp(1),CARD_EDGE)
        }
        root.addView(this,params(-1,-2,12))
    }
    private fun label(text:String,size:Float,color:Int,bold:Boolean):TextView = TextView(activity).apply{
        this.text=text; textSize=size;setTextColor(color)
        if(bold)typeface=Typeface.DEFAULT_BOLD
        gravity=Gravity.CENTER_VERTICAL
    }
    private fun gradient(colors:IntArray,radius:Int):GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,colors
    ).apply{cornerRadius=dp(radius).toFloat()}
    private fun outlined(fill:Int,edge:Int,radius:Int):GradientDrawable = GradientDrawable().apply{
        setColor(fill);cornerRadius=dp(radius).toFloat();setStroke(dp(1),edge)
    }
    private fun horizontal()=LinearLayout(activity).apply{orientation=LinearLayout.HORIZONTAL}
    private fun params(w:Int,h:Int,top:Int=0)=LinearLayout.LayoutParams(if(w<0)w else dp(w),if(h<0)h else dp(h)).apply{topMargin=dp(top)}
    private fun dp(value:Int)=(value*activity.resources.displayMetrics.density+0.5f).toInt()

    private inner class RingMotorButton:View(activity){
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        var running=false
            set(value){field=value;invalidate()}
        init{setLayerType(LAYER_TYPE_SOFTWARE,null);isClickable=true;isFocusable=true}
        override fun onDraw(canvas:Canvas){
            super.onDraw(canvas)
            val cx=width/2f;val cy=height/2f
            val rad=kotlin.math.min(width,height)*0.354f
            val rim=if(running)RED else 0xff10BDFF.toInt()
            val glow=if(running)0x66E92776 else 0x553D82F4
            paint.reset();paint.isAntiAlias=true
            paint.shader=RadialGradient(cx,cy,rad*1.5f,
                intArrayOf(glow,0x33295BC6,0x00101C33),floatArrayOf(0f,0.68f,1f),Shader.TileMode.CLAMP)
            canvas.drawCircle(cx,cy,rad*1.5f,paint)
            paint.shader=null;paint.style=Paint.Style.STROKE
            paint.color=rim;paint.strokeWidth=dp(3).toFloat()
            paint.setShadowLayer(dp(10).toFloat(),0f,0f,rim)
            canvas.drawCircle(cx,cy,rad+dp(5),paint)
            paint.clearShadowLayer()
            paint.color=if(running)0xff6C174D.toInt() else 0xff103C62.toInt()
            paint.style=Paint.Style.FILL
            paint.shader=RadialGradient(cx-rad*0.27f,cy-rad*0.39f,rad*1.8f,
                if(running)intArrayOf(0xffDB3779.toInt(),0xff65163C.toInt(),0xff140F29.toInt())
                else intArrayOf(0xff17466D.toInt(),0xff0B2445.toInt(),0xff08152B.toInt()),null,Shader.TileMode.CLAMP)
            canvas.drawCircle(cx,cy,rad,paint)
            paint.shader=null;paint.color=WHITE
            if(running){
                canvas.drawRoundRect(cx-dp(10),cy-dp(29),cx+dp(10),cy-dp(9),dp(3).toFloat(),dp(3).toFloat(),paint)
            }else{
                val triangle=Path().apply{
                    moveTo(cx-dp(9),cy-dp(32));lineTo(cx+dp(16),cy-dp(19));lineTo(cx-dp(9),cy-dp(7));close()
                }
                canvas.drawPath(triangle,paint)
            }
            paint.typeface=Typeface.create("sans-serif",Typeface.BOLD)
            paint.textSize=dp(20).toFloat();paint.textAlign=Paint.Align.CENTER
            canvas.drawText(if(running)"STOP" else "START",cx,cy+dp(29),paint)
        }
    }
}
