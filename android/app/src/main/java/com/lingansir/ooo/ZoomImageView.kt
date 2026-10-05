package com.lingansir.ooo

import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

class ZoomImageView(context: Context) : AppCompatImageView(context) {
    private var zoom=1f
    private var offsetX=0f
    private var offsetY=0f
    private var lastX=0f
    private var lastY=0f
    private val detector=ScaleGestureDetector(context,object: ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoom=(zoom*detector.scaleFactor).coerceIn(1f,5f)
            constrain(); invalidate(); return true
        }
    })
    init { scaleType=ScaleType.FIT_CENTER }
    override fun onDraw(canvas: Canvas) {
        val state=canvas.save()
        canvas.translate(offsetX,offsetY); canvas.scale(zoom,zoom,width/2f,height/2f)
        super.onDraw(canvas); canvas.restoreToCount(state)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        detector.onTouchEvent(event)
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX=event.x; lastY=event.y }
            MotionEvent.ACTION_MOVE -> {
                if(!detector.isInProgress && event.pointerCount==1) {
                    offsetX+=event.x-lastX; offsetY+=event.y-lastY; constrain(); invalidate()
                }
                lastX=event.x; lastY=event.y
            }
            MotionEvent.ACTION_POINTER_UP -> { val index=if(event.actionIndex==0)1 else 0; lastX=event.getX(index); lastY=event.getY(index) }
            MotionEvent.ACTION_UP -> performClick()
        }
        return true
    }
    private fun constrain() { offsetX=offsetX.coerceIn(-width*(zoom-1)/2,width*(zoom-1)/2); offsetY=offsetY.coerceIn(-height*(zoom-1)/2,height*(zoom-1)/2) }
    override fun performClick(): Boolean { super.performClick(); return true }
}
