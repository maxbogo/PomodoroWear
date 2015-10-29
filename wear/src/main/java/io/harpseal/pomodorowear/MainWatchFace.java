/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.harpseal.pomodorowear;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Vibrator;
import android.provider.CalendarContract;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.MessageApi;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Analog watch face with a ticking second hand. In ambient mode, the second hand isn't shown. On
 * devices with low-bit ambient mode, the hands are drawn without anti-aliasing in ambient mode.
 */
public class MainWatchFace extends CanvasWatchFaceService {
    private static final String TAG = "MainWatchFace";

//    private class CalendarItem
//    {
//        public long id = 0;
//        public String name = "";
//        public String accountName = "";
//        public int color = 0;
//        public CalendarItem(long _id,String _name,String accName,int _color)
//        {
//            id = _id;
//            name = _name;
//            accountName = accName;
//            color = _color;
//        }
//        @Override
//        public String toString()
//        {
//            return name;
//        }
//
//    }
//
//    private class EventItem
//    {
//        public long calID;
//        public String title = "";
//        public String description;
//        public long eventStart;
//        public long eventEnd;
//        public int allDay;
//
//        public EventItem(long _calID,String _title,String _description,long _eventStart,long _eventEnd,int _allDay)
//        {
//            calID = _calID;
//            title = _title;
//            description = _description;
//            eventStart = _eventStart;
//            eventEnd = _eventEnd;
//            allDay = _allDay;
//        }
//        @Override
//        public String toString()
//        {
//            return title;
//        }
//    }

    //Date -> yyyyMMdd -> Long
    //private HashMap<Long,EventItem> mMapEvent;
    /**
     * Update rate in milliseconds for interactive mode. We update once a second to advance the
     * second hand.
     */
    private long mInteractiveUpdateRateMs = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    private int mDataTomatoWork = WatchFaceUtil.DEFAULT_TOMATO_WORK;
    private int mDataTomatoRelax = WatchFaceUtil.DEFAULT_TOMATO_RELAX;
    private int mDataTomatoRelaxLong = WatchFaceUtil.DEFAULT_TOMATO_RELAX_LONG;

    private long mDataTomatoDateStart = WatchFaceUtil.DEFAULT_TIMER_ZERO;
    private long mDataTomatoDateEnd = WatchFaceUtil.DEFAULT_TIMER_ZERO;

    private int mDataTimer1 = WatchFaceUtil.DEFAULT_TIMER1;
    private int mDataTimer2 = WatchFaceUtil.DEFAULT_TIMER2;
    private int mDataTimer3 = WatchFaceUtil.DEFAULT_TIMER3;
    private int mDataTimer4 = WatchFaceUtil.DEFAULT_TIMER4;

    private long mDataTimerDateStart = WatchFaceUtil.DEFAULT_TIMER_ZERO;
    private long mDataTimerDateEnd = WatchFaceUtil.DEFAULT_TIMER_ZERO;

    private long mCalendarID = WatchFaceUtil.DEFAULT_TOMATO_CALENDAR_ID;
    private String mCalendarName = WatchFaceUtil.DEFAULT_TOMATO_CALENDAR_NAME;
    private int mCalendarColor = WatchFaceUtil.DEFAULT_TOMATO_CALENDAR_COLOR;

    private String mTomatoType = WatchFaceUtil.DEFAULT_TOMATO_TYPE;

    private String[] mTomatoTags = WatchFaceUtil.DEFAULT_TOMATO_TAGS;
    //private String[] mTomatoEvents = WatchFaceUtil.DEFAULT_TOMATO_EVENTS;
    private ArrayList<DataMap> mTomatoEventQueue = null;

    private long mBatteryPredictionStartTime = 0;
    private float mBatteryPredictionStartLevel = -1;
    private float mBatteryPredictionCurrentLevel = -1;
    private float mBatteryPredictionHourLeft = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener{

        GoogleApiClient mGoogleApiClient = new GoogleApiClient.Builder(MainWatchFace.this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Wearable.API)
                .build();

        Paint mBackgroundPaint;
        private Bitmap mBackgroundBitmap = null;
        private Bitmap mBackgroundBitmapAmbient = null;
        private Bitmap mBitmapSec = null;
        private Bitmap mBitmapMin = null;
        private Bitmap mBitmapHour = null;

        //Cache
        short mCacheLastUpeateHour = -1;
        private Bitmap mCacheBitmapHour = null;
        short mCacheLastUpeateMin = -1;
        private Bitmap mCacheBitmapMin = null;

        short mCacheLastUpeateAmbienHour = -1;
        private Bitmap mCacheBitmapAmbientHour = null;

        Canvas mCacheCanvas = new Canvas();


        int mInteractionOverlayDir = 0;
        Paint mInteractionOverlayPaint;
        Paint mInteractionTextPaint;

        Paint mHandPaint;
        Paint mHandPaintSec;
        Paint mHandPaintMin;
        Paint mHandPaintHour;
        Paint mHandPaintMeter;
        float mHandWidthMeterSec;
        float mHandWidthMeterMin;
        float mHandWidthMeterHour;
        boolean mAmbient;
        //Time mTime;
        private long mClockShift = 0;
        private long mClockBase = 0;
        private String mClockWeek = "";
        private String mClockDay = "";
        private TimeZone mTimeZone = null;

        private long mTimerMS = Long.MAX_VALUE;
        private long mTomatoMS = Long.MAX_VALUE;

        private int mTouchWidth = 0;
        private int mTouchHeight = 0;


        private int mTouchCoordinateX = 0;
        private int mTouchCoordinateY = 0;

        final Handler mUpdateTimeHandler = new EngineHandler(this);

        Vibrator mVibrator = null;


        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTimeZone = TimeZone.getTimeZone(intent.getStringExtra("time-zone"));
                updateClock(mTimeZone);
            }
        };

        final BroadcastReceiver mServiceMsgReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                //mTime.clear(intent.getStringExtra("time-zone"));
                //mTime.setToNow();

                //String msg = intent.getStringExtra("MessageDataMap");
                byte[] rawData = intent.getByteArrayExtra("MessageDataMap");
                // It's allowed that the message carries only some of the keys used in the config DataItem
                // and skips the ones that we don't want to change.
                if (rawData != null)
                {
                    DataMap configKeysToOverwrite = DataMap.fromByteArray(rawData);
                    Log.d(TAG,"mServiceMsgReceiver onReceive :[" +configKeysToOverwrite.toString()+ "]");
                }
                else
                {
                    Log.d(TAG,"mServiceMsgReceiver onReceive get unknown data");
                }
            }
        };
        boolean mRegisteredTimeZoneReceiver = false;




        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;


        WatchFaceUtil.WatchControlState mControlState = WatchFaceUtil.WatchControlState.WCS_IDLE;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            //http://developer.android.com/reference/android/support/wearable/watchface/WatchFaceStyle.html
            setWatchFaceStyle(new WatchFaceStyle.Builder(MainWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_SHORT)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                            //.setHotwordIndicatorGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL)
                            //.setStatusBarGravity(Gravity.TOP)
                    .build());

            mVibrator = (Vibrator) getApplication().getSystemService(Service.VIBRATOR_SERVICE);

            Resources resources = MainWatchFace.this.getResources();

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.analog_background));

            mInteractionTextPaint = new Paint();
            mInteractionTextPaint.setColor(Color.WHITE);
            mInteractionTextPaint.setTextAlign(Paint.Align.CENTER);
            mInteractionTextPaint.setAntiAlias(true);

            mBackgroundBitmap = null;//BitmapFactory.decodeResource(getResources(), R.drawable.face0_bg);
            mBitmapSec = BitmapFactory.decodeResource(getResources(), R.drawable.face0_sec);
            mBitmapMin = BitmapFactory.decodeResource(getResources(), R.drawable.face0_min);
            mBitmapHour = BitmapFactory.decodeResource(getResources(), R.drawable.face0_hour);

            mInteractionOverlayPaint = new Paint();
            mInteractionOverlayPaint.setColor(Color.BLACK);
            mInteractionOverlayPaint.setAlpha(0);
            mInteractionOverlayPaint.setAntiAlias(true);


            mHandPaint = new Paint();
            mHandPaint.setColor(resources.getColor(R.color.analog_hands));
            mHandPaint.setStrokeWidth(resources.getDimension(R.dimen.analog_hand_stroke));
            mHandPaint.setAntiAlias(true);
            mHandPaint.setStrokeCap(Paint.Cap.ROUND);

            mHandPaintMeter = new Paint();
            mHandPaintMeter.setColor(resources.getColor(R.color.analog_meter));
            mHandPaintMeter.setStrokeWidth(resources.getDimension(R.dimen.analog_hand_meter_min));
            mHandPaintMeter.setAntiAlias(true);
            mHandPaintMeter.setStrokeCap(Paint.Cap.ROUND);

            mHandWidthMeterSec = resources.getDimension(R.dimen.analog_hand_meter_sec);
            mHandWidthMeterMin = resources.getDimension(R.dimen.analog_hand_meter_min);
            mHandWidthMeterHour = resources.getDimension(R.dimen.analog_hand_meter_hour);


            mHandPaintSec = new Paint();
            mHandPaintSec.setColor(resources.getColor(R.color.analog_hands));
            mHandPaintSec.setStrokeWidth(resources.getDimension(R.dimen.analog_hand_stroke_sec));
            mHandPaintSec.setAntiAlias(true);
            mHandPaintSec.setStrokeCap(Paint.Cap.ROUND);

            mHandPaintMin = new Paint();
            mHandPaintMin.setColor(resources.getColor(R.color.analog_hands));
            mHandPaintMin.setStrokeWidth(resources.getDimension(R.dimen.analog_hand_stroke_min));
            mHandPaintMin.setAntiAlias(true);
            mHandPaintMin.setStrokeCap(Paint.Cap.ROUND);

            mHandPaintHour = new Paint();
            mHandPaintHour.setColor(resources.getColor(R.color.analog_hands));
            mHandPaintHour.setStrokeWidth(resources.getDimension(R.dimen.analog_hand_stroke_hour));
            mHandPaintHour.setAntiAlias(true);
            mHandPaintHour.setStrokeCap(Paint.Cap.ROUND);

            updateClock(mTimeZone);
            //mTime = new Time();
        }

        private void updateClock(TimeZone timeZone)
        {

            Calendar cal = Calendar.getInstance(Locale.getDefault());
            cal.setTimeInMillis(System.currentTimeMillis());
            cal.set(Calendar.HOUR_OF_DAY, 0);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            mClockBase = cal.getTimeInMillis();
            if (timeZone == null)
                mClockShift = 0;
            else
            {
                cal.setTimeZone(timeZone);
                cal.set(Calendar.HOUR_OF_DAY, 0);
                cal.set(Calendar.MINUTE, 0);
                cal.set(Calendar.SECOND, 0);
                cal.set(Calendar.MILLISECOND, 0);
                mClockShift = cal.getTimeInMillis()-mClockBase;
            }

            mClockDay = DateFormat.format("dd", cal).toString();
            mClockWeek = DateFormat.format("EE", cal).toString();
        }


        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onApplyWindowInsets: " + (insets.isRound() ? "round" : "square"));
            }
            super.onApplyWindowInsets(insets);

            /** Loads offsets / text size based on device type (square vs. round). */
//            Resources resources = MainWatchFace.this.getResources();
//            boolean isRound = insets.isRound();
//            mXOffset = resources.getDimension(
//                    isRound ? R.dimen.interactive_x_offset_round : R.dimen.interactive_x_offset);
//            mYOffset = resources.getDimension(
//                    isRound ? R.dimen.interactive_y_offset_round : R.dimen.interactive_y_offset);

        }


        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Tap Command: " + tapType);
            }

            mTouchCoordinateX = x;
            mTouchCoordinateY = y;

            switch(tapType) {
                case TAP_TYPE_TOUCH:
                    //Log.d(TAG,"TOUCH @ "+mTouchCoordinateX+" , " + mTouchCoordinateY);
                    //mTouchCommandTotal++;
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    //Log.d(TAG,"TOUCH_CANCEL @ "+mTouchCoordinateX+" , " + mTouchCoordinateY);
                    //++;
                    break;
                case TAP_TYPE_TAP:

                    //mTapCommandTotal++;
//                    if (mTouchCoordinateX<mTouchWidth/2 && mTouchCoordinateY<mTouchHeight/2)
//                        mInteractiveUpdateRateMs = TimeUnit.SECONDS.toMillis(1);
//                    else if (mTouchCoordinateX<mTouchWidth/2 && mTouchCoordinateY>mTouchHeight/2)
//                        mInteractiveUpdateRateMs = TimeUnit.SECONDS.toMillis(1)/4;
//                    else if (mTouchCoordinateX>mTouchWidth/2 && mTouchCoordinateY<mTouchHeight/2)
//                        mInteractiveUpdateRateMs = TimeUnit.SECONDS.toMillis(1)/8;
//                    else
//                        mInteractiveUpdateRateMs = TimeUnit.SECONDS.toMillis(1)/16;

                    //new SendActivityPhoneMessage("WearBatteryTap",52).start();

                    //Log.d(TAG,"TAP @ "+mTouchCoordinateX+" , " + mTouchCoordinateY + "  " + mInteractiveUpdateRateMs);
                    if (mAmbient);
                    else if (mInteractionOverlayPaint.getAlpha() < 100)
                    {
                        if (mControlState == WatchFaceUtil.WatchControlState.WCS_IDLE)
                        {
                            if (mTouchCoordinateX<mTouchWidth/2)
                                mControlState = WatchFaceUtil.WatchControlState.WCS_TOMATO;
                            else
                                mControlState = WatchFaceUtil.WatchControlState.WCS_TIMER;
                        }
                        mInteractiveUpdateRateMs = 33;
                        mInteractionOverlayDir = 200*33/200;
                        updateTimer();
                    }
                    else if (mInteractionOverlayPaint.getAlpha() >= 100)
                    {
                        Long timeInMillis = System.currentTimeMillis();
                        if (mControlState == WatchFaceUtil.WatchControlState.WCS_TOMATO)
                        {
                            //mCurrentCalender.setTime(new Date());
                            boolean isModifiedConfig = false;
                            long dtstart,dtend;
                            dtstart = 0;
                            dtend = 0;

                            if (mDataTomatoDateStart == 0 || mDataTomatoDateEnd == 0 || mTomatoType.equals(WatchFaceUtil.KEY_TOMATO_IDLE))
                            {
                                if (mTouchCoordinateY<mTouchHeight/2)
                                {
                                    mDataTomatoDateStart = timeInMillis;
                                    mDataTomatoDateEnd = mDataTomatoDateStart+ mDataTomatoWork *1000;
                                    mTomatoType = WatchFaceUtil.KEY_TOMATO_WORK;
                                    isModifiedConfig = true;
                                }
                                //canvas.drawText("Start to work!", centerX, centerY-height/6, mInteractionTextPaint);
                                //canvas.drawText("Cancel", centerX, centerY+height/4,mInteractionTextPaint);
                            }
                            else if (timeInMillis>mDataTomatoDateEnd)//out of time
                            {
                                if (mTomatoType.equals(WatchFaceUtil.KEY_TOMATO_WORK))
                                {
                                    if (mTouchCoordinateY<mTouchHeight/2)
                                    {
                                        dtstart = mDataTomatoDateStart;
                                        dtend = mDataTomatoDateEnd;
                                        mDataTomatoDateStart = timeInMillis;
                                        mDataTomatoDateEnd = mDataTomatoDateStart+ mDataTomatoRelaxLong *1000;
                                        mTomatoType = WatchFaceUtil.KEY_TOMATO_RELAX_LONG;
                                        isModifiedConfig = true;
                                    }
                                    else if (mTouchCoordinateX>mTouchWidth/2)
                                    {
                                        dtstart = mDataTomatoDateStart;
                                        dtend = mDataTomatoDateEnd;

                                        mDataTomatoDateStart = timeInMillis;
                                        mDataTomatoDateEnd = mDataTomatoDateStart+ mDataTomatoRelax *1000;
                                        mTomatoType = WatchFaceUtil.KEY_TOMATO_RELAX;
                                        isModifiedConfig = true;
                                    }

                                    //canvas.drawText("Long relax!", centerX, centerY-height/6, mInteractionTextPaint);
                                    //canvas.drawText("Cancel", centerX-width/4, centerY+height/4,mInteractionTextPaint);
                                    //canvas.drawText("Relax", centerX+width/4, centerY+height/4,mInteractionTextPaint);
                                }
                                else // relax , long relax
                                {
                                    if (mTouchCoordinateY<mTouchHeight/2)
                                    {
                                        mDataTomatoDateStart = timeInMillis;
                                        mDataTomatoDateEnd = mDataTomatoDateStart+ mDataTomatoWork *1000;
                                        mTomatoType = WatchFaceUtil.KEY_TOMATO_WORK;
                                        isModifiedConfig = true;
                                    }
                                    else if (mTouchCoordinateX>mTouchWidth/2)
                                    {
                                        mDataTomatoDateStart = mDataTomatoDateEnd = 0;
                                        mTomatoType = WatchFaceUtil.KEY_TOMATO_IDLE;
                                        isModifiedConfig = true;
                                    }
                                    //canvas.drawText("Start to work!", centerX, centerY-height/6, mInteractionTextPaint);
                                    //canvas.drawText("Cancel", centerX-width/4, centerY+height/4,mInteractionTextPaint);
                                    //canvas.drawText("Idle", centerX+width/4, centerY+height/4,mInteractionTextPaint);
                                }
                            }
                            else
                            {
                                if (mTouchCoordinateY<mTouchHeight/2)
                                {
                                    mDataTomatoDateStart = timeInMillis;
                                    mDataTomatoDateEnd = mDataTomatoDateStart+ mDataTomatoWork *1000;
                                    mTomatoType = WatchFaceUtil.KEY_TOMATO_WORK;
                                    isModifiedConfig = true;
                                }
                                else if (mTouchCoordinateX>mTouchWidth/2)
                                {
                                    mDataTomatoDateStart = mDataTomatoDateEnd = 0;
                                    mTomatoType = WatchFaceUtil.KEY_TOMATO_IDLE;
                                    isModifiedConfig = true;
                                }
                                //canvas.drawText("Start to work!", centerX, centerY-height/6, mInteractionTextPaint);
                                //canvas.drawText("Cancel", centerX-width/4, centerY+height/4,mInteractionTextPaint);
                                //canvas.drawText("Idle", centerX+width/4, centerY+height/4,mInteractionTextPaint);
                            }
                            if (isModifiedConfig)
                            {
                                mCacheLastUpeateHour = -1;
                                mCacheLastUpeateMin = -1;
                                mCacheLastUpeateAmbienHour = -1;
                                DataMap configKeysToOverwrite = new DataMap();
                                configKeysToOverwrite.putLong(WatchFaceUtil.KEY_TOMATO_DATE_START, mDataTomatoDateStart);
                                configKeysToOverwrite.putLong(WatchFaceUtil.KEY_TOMATO_DATE_END, mDataTomatoDateEnd);
                                configKeysToOverwrite.putString(WatchFaceUtil.KEY_TOMATO_TYPE,mTomatoType);
                                WatchFaceUtil.overwriteKeysInConfigDataMap(mGoogleApiClient, configKeysToOverwrite);
                            }

                            if (dtstart!=0 && dtend>dtstart)
                            {
                                    Intent i = new Intent(getApplicationContext(), TomatoBuilderActivity.class);
                                    i.putExtra(CalendarContract.Events.DTSTART,dtstart);
                                    i.putExtra(CalendarContract.Events.DTEND,timeInMillis);
                                    i.putExtra(CalendarContract.Events.CALENDAR_ID,mCalendarID);
                                    i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                    startActivity(i);
                            }
                        }
                        else if (mControlState == WatchFaceUtil.WatchControlState.WCS_TIMER) {

                            boolean isModifiedConfig = false;
                            if (mDataTimerDateStart == 0 || mDataTimerDateEnd == 0)
                            {
                                //mCurrentCalender.setTime(new Date());
                                mDataTimerDateStart = timeInMillis;
                                isModifiedConfig = true;
                                if (mTouchCoordinateX<mTouchWidth/2 && mTouchCoordinateY<mTouchHeight/2)
                                {
                                    mDataTimerDateEnd =mDataTimerDateStart+ mDataTimer1 *1000;
                                    //Log.d(TAG,"Timer1");
                                }
                                else if (mTouchCoordinateX>mTouchWidth/2 && mTouchCoordinateY<mTouchHeight/2)
                                {
                                    mDataTimerDateEnd =mDataTimerDateStart+ mDataTimer2 *1000;
                                    //Log.d(TAG,"Timer2");
                                }
                                else if (mTouchCoordinateX>mTouchWidth/2 && mTouchCoordinateY>mTouchHeight/2)
                                {
                                    mDataTimerDateEnd =mDataTimerDateStart+ mDataTimer3 *1000;
                                    //Log.d(TAG,"Timer3");
                                }
                                else {
                                    mDataTimerDateEnd =mDataTimerDateStart+ mDataTimer4 *1000;
                                    //Log.d(TAG, "Timer4");

                                }

                                DataMap configKeysToOverwrite = new DataMap();
                                configKeysToOverwrite.putLong(WatchFaceUtil.KEY_TIMER_DATE_START, mDataTimerDateStart);
                                configKeysToOverwrite.putLong(WatchFaceUtil.KEY_TIMER_DATE_END, mDataTimerDateEnd);
                                WatchFaceUtil.overwriteKeysInConfigDataMap(mGoogleApiClient, configKeysToOverwrite);

                            }
                            else
                            {

                                if (mTouchCoordinateY<mTouchHeight/2)
                                {
                                    mDataTimerDateStart = mDataTimerDateEnd = 0;

                                    isModifiedConfig = true;
                                    //Log.d(TAG,"Idle");
                                }
                                else if (mTouchCoordinateX>mTouchWidth/2 && mTouchCoordinateY>mTouchHeight/2)
                                {
                                    long timeDiff = mDataTimerDateEnd - mDataTimerDateStart;
                                    mDataTimerDateStart = timeInMillis;
                                    mDataTimerDateEnd =mDataTimerDateStart+ timeDiff;
                                    isModifiedConfig= true;
                                    //Log.d(TAG,"Reset");
                                }
                                else
                                    ;//Log.d(TAG,"Cancel");


                            }
                            if (isModifiedConfig)
                            {
                                mCacheLastUpeateHour = -1;
                                mCacheLastUpeateMin = -1;
                                mCacheLastUpeateAmbienHour = -1;
                                DataMap configKeysToOverwrite = new DataMap();
                                configKeysToOverwrite.putLong(WatchFaceUtil.KEY_TIMER_DATE_START, mDataTimerDateStart);
                                configKeysToOverwrite.putLong(WatchFaceUtil.KEY_TIMER_DATE_END, mDataTimerDateEnd);
                                WatchFaceUtil.overwriteKeysInConfigDataMap(mGoogleApiClient, configKeysToOverwrite);
                            }
                        }

                        mControlState = WatchFaceUtil.WatchControlState.WCS_IDLE;
                        mInteractiveUpdateRateMs = 33;
                        mInteractionOverlayDir = -200*33/200;
                        updateTimer();
                    }
                    break;

                default:
                    //Log.d(TAG,"default @ "+mTouchCoordinateX+" , " + mTouchCoordinateY);
                    break;
            }

            invalidate();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            updateTimer();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mHandPaint.setAntiAlias(!inAmbientMode);
                    mHandPaintSec.setAntiAlias(!inAmbientMode);
                    mHandPaintMin.setAntiAlias(!inAmbientMode);
                    mHandPaintHour.setAntiAlias(!inAmbientMode);
                    mInteractionTextPaint.setAntiAlias(!inAmbientMode);

                    if (inAmbientMode)
                    {
                        mInteractionOverlayDir = 0;
                        mInteractionOverlayPaint.setAlpha(0);
                        mInteractiveUpdateRateMs = TimeUnit.SECONDS.toMillis(1);
                    }
                }
                updateTimer();
                invalidate();
            }
            else {
                // Whether the timer should be running depends on whether we're visible (as well as
                // whether we're in ambient mode), so we may need to start or stop the timer.
                updateTimer();
            }
        }

        private final Rect textBounds = new Rect(); //don't new this up in a draw method

        public void drawTextCentred(Canvas canvas, Paint paint, String text, float cx, float cy) {
            paint.getTextBounds(text, 0, text.length(), textBounds);
            //canvas.drawText(text, cx - textBounds.exactCenterX(), cy - textBounds.exactCenterY(), paint);
            canvas.drawText(text, cx, cy - textBounds.exactCenterY(), paint);
            //Log.d(TAG,text + " @ " + cx + " x " + cy);
        }

        private void drawTickNumber(Canvas canvas, float centerX, float centerY, float radius,
                                    int angleDegS, int angleDegE, int angleInterval,
                                    int numberS,int numberInterval,
                                    float textSize, int textColor)
        {
            if (angleDegS>angleDegE)
            {
                int angle = angleDegS;
                angleDegS = angleDegE;
                angleDegE = angle;
            }

            mInteractionTextPaint.setTextSize(textSize);
            mInteractionTextPaint.setColor(textColor);

            PointF p = new PointF();

            for (int a=angleDegS,n=numberS; a<=angleDegE;
                 a+=angleInterval,n+=numberInterval)
            {
                float aRad = (float)a*(float)Math.PI/180.f;
                p.set(centerX + radius * (float)Math.sin(aRad),centerY - radius * (float)Math.cos(aRad));
                drawTextCentred(canvas,mInteractionTextPaint,""+n,p.x,p.y);
            }
            //canvas.drawText("Start to work!", centerX, centerY-height/6, mInteractionTextPaint);
        }

        private void drawTickLine(Canvas canvas, float centerX, float centerY, float radiusS, float radiusE,
                                  int angleDegS, int angleDegE, int angleInterval,
                                  int angleSkipModBase, int angleSkipModCount,
                                  float strokeWidth, int strokeColor)
        {
            if (angleDegS>angleDegE)
            {
                int angle = angleDegS;
                angleDegS = angleDegE;
                angleDegE = angle;
            }
            Paint paint = new Paint();
            paint.setColor(strokeColor);
            paint.setStrokeWidth(strokeWidth);
            paint.setAntiAlias(!mAmbient);
            if (mAmbient)
                paint.setStrokeCap(Paint.Cap.SQUARE );
            else
                paint.setStrokeCap(Paint.Cap.ROUND);

            PointF pS = new PointF();
            PointF pE = new PointF();

            for (int a=angleDegS,c=0;a<=angleDegE;a+=angleInterval,c++)
            {
                if (angleSkipModBase>0 && c%angleSkipModBase == angleSkipModCount)
                    continue;

                float aRad = (float)a*(float)Math.PI/180.f;

                pS.set(centerX + radiusS * (float)Math.sin(aRad),centerY - radiusS * (float)Math.cos(aRad));
                pE.set(centerX + radiusE * (float)Math.sin(aRad),centerY - radiusE * (float)Math.cos(aRad));

                canvas.drawLine(pS.x,pS.y,pE.x,pE.y,paint);
            }
        }

        private int DRAW_DAY = 0x1;
        private int DRAW_HOUR = 0x2;
        private int DRAW_MIN = 0x4;
        private int DRAW_SEC = 0x8;
        private int DRAW_ALL = 0xf;
        private int DRAW_RESET = 0x10;

        private int mUpdateFlag = 0;


        private void drawMeter(Canvas canvas, int drawFlag, long timeMS, float centerX,float centerY,
                               float meterLenHr,float meterLenMin,float meterLenSec,int colorInTime,int colorTimeOut)
        {
            if (timeMS == Long.MAX_VALUE)
            {
                if ((drawFlag & DRAW_HOUR)!=0) {
                    mHandPaintMeter.setColor(Color.GRAY);
                    canvas.drawCircle(centerX, centerY, (float) mHandPaintMeter.getStrokeWidth() * 1.5f, mHandPaintMeter);
                    //canvas.drawLine(centerX, centerY, centerX, centerY - meterLenMin, mHandPaintMeter);
                }
            }
            else
            {

                if (timeMS>=0)
                    mHandPaintMeter.setColor(colorInTime);
                else
                {
                    mHandPaintMeter.setColor(colorTimeOut);
                    timeMS*=-1;
                }

                int timerSec = (int)(timeMS/1000);//sec
                if (timerSec>60*60*12)
                    timerSec=60*60*12;

                float meterRot;
                float meterX,meterY;

                if ((drawFlag & DRAW_HOUR)!=0 && timerSec>3600)//hour
                {
                    //meterRot = ((mTime.hour + (minutes / 60f)) / 6f) * (float) Math.PI;

                    meterRot = (float)timerSec/3600f / 6f * (float) Math.PI;
                    mHandPaintMeter.setStrokeWidth(mHandWidthMeterHour);
                    meterX = (float) Math.sin(meterRot) * meterLenHr;
                    meterY = (float) -Math.cos(meterRot) * meterLenHr;
                    canvas.drawLine(
                            centerX, centerY,
                            centerX + meterX, centerY + meterY, mHandPaintMeter);

                }

                if ((drawFlag & DRAW_MIN)!=0) {
                    meterRot = (float) ((timerSec / 60) % 60) / 30f * (float) Math.PI;


                    meterX = (float) Math.sin(meterRot) * meterLenMin;
                    meterY = (float) -Math.cos(meterRot) * meterLenMin;
                    if (!mAmbient && timerSec > 3600) {
                        mHandPaintMeter.setStrokeWidth(mHandWidthMeterMin + 2);
                        int colorTemp = mHandPaintMeter.getColor();
                        mHandPaintMeter.setColor(0xff000000);
                        canvas.drawLine(
                                centerX, centerY,
                                centerX + meterX, centerY + meterY, mHandPaintMeter);

                        mHandPaintMeter.setColor(colorTemp);
                    }

                    mHandPaintMeter.setStrokeWidth(mHandWidthMeterMin);
                    canvas.drawLine(
                            centerX, centerY,
                            centerX + meterX, centerY + meterY, mHandPaintMeter);
                    canvas.drawCircle(centerX, centerY, (float) mHandPaintMeter.getStrokeWidth() * 1.5f, mHandPaintMeter);
                }

                if ((drawFlag & DRAW_SEC)!=0 && !mAmbient) {
                    meterRot = (float) timerSec % 60 / 30f * (float) Math.PI;
                    //Log.d(TAG, "Show sec : " + timerInt + " angle : " + meterRot + " " + (mDataTimerDateStart == mDataTimerDateEnd));
                    mHandPaintMeter.setStrokeWidth(mHandWidthMeterSec);
                    meterX = (float) Math.sin(meterRot) * meterLenSec;
                    meterY = (float) -Math.cos(meterRot) * meterLenSec;
                    canvas.drawLine(
                            centerX, centerY,
                            centerX + meterX, centerY + meterY, mHandPaintMeter);
                }

                mHandPaintMeter.setStrokeWidth(mHandWidthMeterMin);
            }
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {



            //Log.d(TAG,"Canvas " + canvas.getClipBounds().width() + " " + canvas.getClipBounds().height() );
            //final boolean isDrawDirect = true;
            int width = bounds.width();
            int height = bounds.height();
            float centerX = width / 2f;
            float centerY = height / 2f;

            mTouchWidth = width;
            mTouchHeight = height;
            //Resources resources = MainWatchFace.this.getResources();


            int hours,minutes,seconds;
            if (System.currentTimeMillis() - mClockBase > 24*60*60*1000)
                updateClock(mTimeZone);

            seconds = (int)((System.currentTimeMillis() - mClockBase + mClockShift)/1000);
            minutes = seconds/60;
            seconds %= 60;
            hours = minutes/60;
            minutes %= 60;

            final float meterXShiftL = 5;
            final float meterXShiftR = 7;

            final float meterLengthSec = centerX * 128.f/400.f;
            final float meterLength = meterLengthSec -10;
            final float meterLengthHour = meterLengthSec - 20;

            final float timerX = centerX + width *51.f/400.f,timerY = centerY - width *76.f/400.f;
            final float tomatoX = centerX - width / 4+meterXShiftL,tomatoY = centerY;

            final float meterYShift = (float)height*66.f/320.f;//battery

            if (mBackgroundBitmap == null)
            {
                mBackgroundBitmap = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.RGB_565);
                mCacheCanvas.setBitmap(mBackgroundBitmap);
                drawTickLine(mCacheCanvas, centerX, centerY, centerX - 8, centerX, 0, 360, 6, 5, 0, 1, 0xffffffff);
                drawTickLine(mCacheCanvas, centerX, centerY, centerX - 14, centerX, 0, 360, 30, 3, 0, 3, 0xffffffff);
                drawTickLine(mCacheCanvas, centerX, centerY, centerX - 16, centerX, 0, 360, 90, -1, 0, 5, 0xffffffff);
                //drawTickNumber(mCacheCanvas,centerX,centerY,centerX - 16,30,360,30,1,1,24, 0xffffffff);

                drawTickLine(mCacheCanvas, centerX, centerY + meterYShift, meterLengthSec - 4, meterLengthSec, 90, 270, 18, 5, 0, 1, 0xffffffff);
                //drawTickLine(mCacheCanvas, centerX, centerY + meterYShift, meterLengthSec - 6, meterLengthSec, 90, 270, 90, -1, 0, 2, 0xffffffff);

                drawTickLine(mCacheCanvas, timerX, timerY, meterLengthSec - 4, meterLengthSec, 0, 360, 30, 3, 0, 1, 0xffffffff);
                //drawTickLine(mCacheCanvas, timerX, timerY, meterLengthSec - 6, meterLengthSec, 0, 360, 90, -1, 0, 2, 0xffffffff);

                drawTickLine(mCacheCanvas, tomatoX, tomatoY, meterLengthSec - 4, meterLengthSec, 0, 360, 30, 3, 0, 1, 0xffffffff);
                //drawTickLine(mCacheCanvas, tomatoX, tomatoY, meterLengthSec - 6, meterLengthSec, 0, 360, 90, -1, 0, 2, 0xffffffff);

                mInteractionTextPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
                drawTickNumber(mCacheCanvas, centerX, centerY + meterYShift, meterLengthSec, 90, 270, 90,  0, 50,  14, 0xffffffff);
                drawTickNumber(mCacheCanvas,timerX, timerY,meterLengthSec,0,270,90,  0,15, 14, 0xffffffff);
                drawTickNumber(mCacheCanvas,tomatoX, tomatoY,meterLengthSec,0,270,90,  0,15,  14, 0xffffffff);
                mInteractionTextPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));

            }
            else if (mBackgroundBitmap != null && mBackgroundBitmap.getWidth()!=width) {
                mBackgroundBitmap = Bitmap.createScaledBitmap(mBackgroundBitmap, width, mBackgroundBitmap.getHeight() * width / mBackgroundBitmap.getWidth(), true);
            }

            if (mAmbient && mBackgroundBitmapAmbient == null) {
                mBackgroundBitmapAmbient = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.RGB_565);
                mCacheCanvas.setBitmap(mBackgroundBitmapAmbient);
                drawTickLine(mCacheCanvas, centerX, centerY, centerX - 8, centerX, 0, 360, 6, 5, 0, 1, 0xffffffff);
                drawTickLine(mCacheCanvas, centerX, centerY, centerX - 14, centerX, 0, 360, 30, 3, 0, 3, 0xffffffff);
                drawTickLine(mCacheCanvas, centerX, centerY, centerX - 16, centerX, 0, 360, 90, -1, 0, 5, 0xffffffff);
                //drawTickNumber(mCacheCanvas,centerX,centerY,centerX - 16,30,360,30,1,1,24, 0xffffffff);

                drawTickLine(mCacheCanvas, centerX, centerY + meterYShift, meterLengthSec - 4, meterLengthSec, 90, 270, 18, 5, 0, 1, 0xffffffff);
                //drawTickLine(mCacheCanvas, centerX, centerY + meterYShift, meterLengthSec - 6, meterLengthSec, 90, 270, 90, -1, 0, 2, 0xffffffff);

                drawTickLine(mCacheCanvas, timerX, timerY, meterLengthSec - 4, meterLengthSec, 0, 360, 30, 3, 0, 1, 0xffffffff);
                //drawTickLine(mCacheCanvas, timerX, timerY, meterLengthSec - 6, meterLengthSec, 0, 360, 90, -1, 0, 2, 0xffffffff);

                drawTickLine(mCacheCanvas, tomatoX, tomatoY, meterLengthSec - 4, meterLengthSec, 0, 360, 30, 3, 0, 1, 0xffffffff);
                //drawTickLine(mCacheCanvas, tomatoX, tomatoY, meterLengthSec - 6, meterLengthSec, 0, 360, 90, -1, 0, 2, 0xffffffff);

                mInteractionTextPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
                drawTickNumber(mCacheCanvas, centerX, centerY + meterYShift, meterLengthSec, 90, 270, 90, 0, 50, 14, 0xffffffff);
                drawTickNumber(mCacheCanvas, timerX, timerY, meterLengthSec, 0, 270, 90, 0, 15, 14, 0xffffffff);
                drawTickNumber(mCacheCanvas, tomatoX, tomatoY, meterLengthSec, 0, 270, 90, 0, 15, 14, 0xffffffff);
                mInteractionTextPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));

            }

            if (mBitmapSec != null && mBitmapSec.getWidth()!=width) {
                float scale;
                scale = (float)width/(float)mBitmapSec.getWidth();
                mBitmapSec = Bitmap.createScaledBitmap(mBitmapSec,
                        (int)((float)mBitmapSec.getWidth()*scale),
                        (int)((float)mBitmapSec.getHeight()*scale), true);
                scale = (float)width/(float)mBitmapMin.getWidth();
                mBitmapMin = Bitmap.createScaledBitmap(mBitmapMin,
                        (int)((float)mBitmapMin.getWidth()*scale),
                        (int)((float)mBitmapMin.getHeight()*scale), true);
                scale = (float)width/(float)mBitmapHour.getWidth();
                mBitmapHour = Bitmap.createScaledBitmap(mBitmapHour,
                        (int)((float)mBitmapHour.getWidth()*scale),
                        (int)((float)mBitmapHour.getHeight()*scale), true);
            }

            int drawFlag = mUpdateFlag;
            mUpdateFlag = 0;
            if (!mAmbient)
                drawFlag |= DRAW_SEC;
            else
                drawFlag |= DRAW_MIN;

            //if (isDrawDirect)
            //    drawFlag = DRAW_ALL;

            if (mAmbient)
            {
                if (mCacheBitmapAmbientHour == null) {
                    mCacheBitmapAmbientHour = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.RGB_565);
                    drawFlag|=DRAW_HOUR;
                }
                if (mCacheLastUpeateAmbienHour != hours)
                    drawFlag|= DRAW_HOUR;

                mCacheLastUpeateAmbienHour = (short)hours;
            }
            else
            {
                if (mCacheBitmapHour == null) {
                    mCacheBitmapHour = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.RGB_565);
                    drawFlag|=DRAW_HOUR|DRAW_MIN;
                }
                if (mCacheBitmapMin == null) {
                    mCacheBitmapMin = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ARGB_8888);//ARGB_8888
                    drawFlag|=DRAW_MIN;
                }


                if (mCacheLastUpeateHour != hours)
                    drawFlag|= DRAW_HOUR|DRAW_MIN;
                if (mCacheLastUpeateMin != minutes)
                    drawFlag|= DRAW_MIN;

                mCacheLastUpeateHour = (short)hours;
                mCacheLastUpeateMin = (short)minutes;
            }






            if ((drawFlag&DRAW_HOUR) != 0)
            {
                Canvas tmpCanvas;

                //if (isDrawDirect)
                //    tmpCanvas = canvas;
                //else
                if (mAmbient) {
                    //mCacheBitmapAmbientHour.eraseColor(0xff000000);
                    mCacheCanvas.setBitmap(mCacheBitmapAmbientHour);
                    tmpCanvas = mCacheCanvas;
                    tmpCanvas.drawBitmap(mBackgroundBitmapAmbient, 0, 0, mBackgroundPaint);
                }
                else {
                    //mCacheBitmapHour.eraseColor(0xff000000);
                    mCacheCanvas.setBitmap(mCacheBitmapHour);
                    tmpCanvas = mCacheCanvas;
                    tmpCanvas.drawBitmap(mBackgroundBitmap, 0, 0, mBackgroundPaint);
                }


                //DateFormat.format("MMM dd yyyy", mCurrentCalender).toString()
                mInteractionTextPaint.setTextSize(height / 8);
                //mInteractionTextPaint.setTypeFace(Typeface.BOLD);
                mInteractionTextPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
                mInteractionTextPaint.setColor(Color.GRAY);
                tmpCanvas.drawText(mClockDay, centerX + width / 4, centerY + height / 10, mInteractionTextPaint);

                float dateHeight = mInteractionTextPaint.descent() - mInteractionTextPaint.ascent();
                mInteractionTextPaint.setTextSize(height / 16);
                tmpCanvas.drawText(mClockWeek, centerX + width / 4, centerY + height / 10 + dateHeight / 2.5f, mInteractionTextPaint);
                mInteractionTextPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));

                drawMeter(tmpCanvas, DRAW_HOUR, mTimerMS,timerX,timerY,meterLengthHour,meterLength,meterLengthSec,0xff00b000,mDataTimerDateStart == mDataTimerDateEnd?0xff00b000:0xffff4040);
                if (mTomatoType.equals(WatchFaceUtil.KEY_TOMATO_WORK))
                    drawMeter(tmpCanvas,DRAW_HOUR,mTomatoMS,tomatoX,tomatoY,meterLengthHour,meterLength,meterLengthSec,0xff00b000,0xffff4040);
                else
                    drawMeter(tmpCanvas,DRAW_HOUR,mTomatoMS,tomatoX,tomatoY,meterLengthHour,meterLength,meterLengthSec,Color.GRAY,Color.YELLOW);
            }

            //if (isDrawDirect);
            //else
            if (mAmbient)
                canvas.drawBitmap(mCacheBitmapAmbientHour, 0, 0, mBackgroundPaint);
            else {
                canvas.drawBitmap(mCacheBitmapHour, 0, 0, mBackgroundPaint);
                drawMeter(canvas, DRAW_SEC, mTimerMS, timerX, timerY, meterLengthHour, meterLength, meterLengthSec, 0xff00b000, mDataTimerDateStart == mDataTimerDateEnd ? 0xff00b000 : 0xffff4040);
                if (mTomatoType.equals(WatchFaceUtil.KEY_TOMATO_WORK))
                    drawMeter(canvas, DRAW_SEC, mTomatoMS, tomatoX, tomatoY, meterLengthHour, meterLength, meterLengthSec, 0xff00b000, 0xffff4040);
                else
                    drawMeter(canvas, DRAW_SEC, mTomatoMS, tomatoX, tomatoY, meterLengthHour, meterLength, meterLengthSec, Color.GRAY, Color.YELLOW);
            }

            //drawFlag|=DRAW_MIN;
            if ((drawFlag&DRAW_MIN) != 0)
            {
                Canvas tmpCanvas;
                //if (isDrawDirect)
                //    tmpCanvas = canvas;
                //else
                if (mAmbient)
                    tmpCanvas = canvas;
                else
                {
                    mCacheBitmapMin.eraseColor(0x00000000);
                    mCacheCanvas.setBitmap(mCacheBitmapMin);
                    tmpCanvas = mCacheCanvas;
                }

                drawMeter(tmpCanvas, DRAW_MIN, mTimerMS,timerX,timerY,meterLengthHour,meterLength,meterLengthSec,0xff00b000,mDataTimerDateStart == mDataTimerDateEnd?0xff00b000:0xffff4040);
                if (mTomatoType.equals(WatchFaceUtil.KEY_TOMATO_WORK))
                    drawMeter(tmpCanvas,DRAW_MIN,mTomatoMS,tomatoX,tomatoY,meterLengthHour,meterLength,meterLengthSec,0xff00b000,0xffff4040);
                else
                    drawMeter(tmpCanvas,DRAW_MIN,mTomatoMS,tomatoX,tomatoY,meterLengthHour,meterLength,meterLengthSec,Color.GRAY,Color.YELLOW);

                float minRot,minRotDeg;
                //minRotDeg = minRot = ((float)minutes + (float)seconds /60f) / 30f;
                minRotDeg = minRot = (float)minutes / 30f;
                minRotDeg *= 180.f;
                minRot *= (float) Math.PI;

                float hrRot = ((hours + (minutes / 60f)) / 6f) * (float) Math.PI;
                float hrRotDeg = ((hours + (minutes / 60f)) / 6f) * 180.f;

                float hrLength = centerX - 80;
                float minLength = centerX - 40;

                float meterX,meterY;
                Intent batteryStatus = MainWatchFace.this.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                if (batteryStatus!=null)
                {
                    float batteryLevel = (float)batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) /
                            (float) batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1)*100.f;
                    //int scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                    float batteryRot = batteryLevel/100.f*(float) Math.PI + (float) Math.PI /2.f;
                    mHandPaintMeter.setColor(0xffff4040);
                    meterX = (float) Math.sin(batteryRot) * meterLength;
                    meterY = (float) -Math.cos(batteryRot) * meterLength;

                    tmpCanvas.drawLine(centerX, centerY + meterYShift, centerX + meterX, centerY + meterY + meterYShift, mHandPaintMeter);
                    tmpCanvas.drawCircle(centerX, centerY + meterYShift, mHandPaintMeter.getStrokeWidth() * 1.5f, mHandPaintMeter);

                    if (mBatteryPredictionStartTime == 0 || mBatteryPredictionStartLevel < 0 || mBatteryPredictionCurrentLevel<batteryLevel-1)
                    {
                        mBatteryPredictionStartTime = System.currentTimeMillis();
                        mBatteryPredictionStartLevel = batteryLevel;
                        mBatteryPredictionCurrentLevel = batteryLevel;
                        mBatteryPredictionHourLeft = -1;
                    }
                    else
                    {
                        if (mBatteryPredictionStartLevel>batteryLevel)
                        {
                            float timeDiffSec = (float)(System.currentTimeMillis() - mBatteryPredictionStartTime)/1000.f;
                            float batteryDiff = mBatteryPredictionStartLevel - batteryLevel;
                            //Log.d(TAG,"batteryDiff " + batteryDiff + "  timeDiffSec " + timeDiffSec);
                            timeDiffSec *= batteryLevel/batteryDiff;
                            mBatteryPredictionHourLeft = timeDiffSec / 3600.f;

                            if (mBatteryPredictionHourLeft>99.9f || mBatteryPredictionHourLeft<0)
                                mBatteryPredictionHourLeft = 99.9f;
                        }
                    }

                    //if (!mAmbient) {
                        mInteractionTextPaint.setTextSize(20);
                        mInteractionTextPaint.setColor(Color.GRAY);
                        if (mBatteryPredictionHourLeft >= 0 && mBatteryPredictionHourLeft <= 99) {
                            if (mBatteryPredictionHourLeft<1)
                                drawTextCentred(tmpCanvas, mInteractionTextPaint,
                                        String.format("%.0fmin", mBatteryPredictionHourLeft*60),
                                        centerX,centerY + meterYShift + meterLengthSec/2 );
                            else
                                drawTextCentred(tmpCanvas, mInteractionTextPaint,
                                        String.format("%.0fhr", mBatteryPredictionHourLeft),
                                        centerX,centerY + meterYShift + meterLengthSec/2 );

                            mInteractionTextPaint.setTextSize(14);
                            drawTextCentred(tmpCanvas, mInteractionTextPaint,
                                    String.format("%.2f %.0fhr",batteryLevel/mBatteryPredictionHourLeft,mBatteryPredictionHourLeft/batteryLevel*100),
                                    centerX,centerY + meterYShift + meterLengthSec/4);
                        }
                        else if (mBatteryPredictionHourLeft < 0)
                            drawTextCentred(tmpCanvas, mInteractionTextPaint, "-- hr",centerX,centerY + meterYShift + meterLengthSec/2 );
                    //}

                    mBatteryPredictionCurrentLevel = batteryLevel;


                }



                if (!mAmbient && mBitmapHour!=null)
                {
                    Matrix matrix = new Matrix();
                    matrix.setRotate(hrRotDeg, mBitmapSec.getWidth() / 2, mBitmapSec.getHeight() / 2);
                    tmpCanvas.drawBitmap(mBitmapHour, matrix, mBackgroundPaint);
                }
                else
                {
                    float hrX = (float) Math.sin(hrRot) * hrLength;
                    float hrY = (float) -Math.cos(hrRot) * hrLength;
                    tmpCanvas.drawLine(centerX, centerY, centerX + hrX, centerY + hrY, mHandPaintHour);
                }

                if (!mAmbient && mBitmapMin!=null)
                {
                    Matrix matrix = new Matrix();

                    matrix.setRotate(minRotDeg, mBitmapMin.getWidth() / 2, mBitmapMin.getHeight() / 2);
                    //matrix.preTranslate(width / 2 - mBitmapMin.getWidth() / 2, width / 2 - mBitmapMin.getWidth() / 2);
                    tmpCanvas.drawBitmap(mBitmapMin, matrix, mBackgroundPaint);
                }
                else {
                    float minX = (float) Math.sin(minRot) * minLength;
                    float minY = (float) -Math.cos(minRot) * minLength;

                    float strockMin = mHandPaintMin.getStrokeWidth();
                    mHandPaintMin.setColor(0xff000000);
                    mHandPaintMin.setStrokeWidth(strockMin+2);
                    tmpCanvas.drawLine(centerX, centerY, centerX + minX, centerY + minY, mHandPaintMin);

                    mHandPaintMin.setColor(0xffffffff);
                    mHandPaintMin.setStrokeWidth(strockMin);
                    tmpCanvas.drawLine(centerX, centerY, centerX + minX, centerY + minY, mHandPaintMin);
                }


            }

            //if (!mAmbient && !isDrawDirect)
            if (!mAmbient)
                canvas.drawBitmap(mCacheBitmapMin, 0, 0, mBackgroundPaint);

            float secLength = centerX - 20;
            float secRot = seconds / 30f * (float) Math.PI;
            float secRotDeg = seconds / 30f * 180.f;

            if (!mAmbient) {
                if (mBitmapSec != null)
                {
                    Matrix matrix = new Matrix();
                    matrix.setRotate(secRotDeg, mBitmapSec.getWidth() / 2, mBitmapSec.getHeight() / 2);
                    canvas.drawBitmap(mBitmapSec, matrix, mBackgroundPaint);
                    //canvas.drawBitmap(mBitmapSec, 0, 0, mBackgroundPaint);
                }
                else
                {
                    float secX = (float) Math.sin(secRot) * secLength;
                    float secY = (float) -Math.cos(secRot) * secLength;
                    canvas.drawLine(centerX, centerY, centerX + secX, centerY + secY, mHandPaint);
                }

            }

            if (mInteractionOverlayDir!=0) {
                int newAlpha = mInteractionOverlayPaint.getAlpha() + mInteractionOverlayDir;
                if (newAlpha < 0) {
                    mInteractionOverlayDir = 0;
                    mInteractionOverlayPaint.setAlpha(0);
                    mInteractiveUpdateRateMs = TimeUnit.SECONDS.toMillis(1);
                } else if (newAlpha > 200) {
                    mInteractionOverlayDir = 0;
                    mInteractionOverlayPaint.setAlpha(200);
                    mInteractiveUpdateRateMs = TimeUnit.SECONDS.toMillis(1);
                }
                else
                    mInteractionOverlayPaint.setAlpha(newAlpha);
            }

            if (!mAmbient)
            {
                if (mInteractionOverlayPaint.getAlpha()!=0)
                    canvas.drawRect(bounds,mInteractionOverlayPaint);
                if (mControlState == WatchFaceUtil.WatchControlState.WCS_TOMATO)
                {
                    mInteractionTextPaint.setColor(Color.WHITE);
                    mInteractionTextPaint.setTextSize(height/10);
                    if (mDataTomatoDateStart == 0 || mDataTomatoDateEnd == 0 || mTomatoType.equals(WatchFaceUtil.KEY_TOMATO_IDLE))
                    {
                        canvas.drawText("Start to work!", centerX, centerY-height/6, mInteractionTextPaint);
                        canvas.drawText("Cancel", centerX, centerY+height/4,mInteractionTextPaint);
                    }
                    else if (System.currentTimeMillis()>mDataTomatoDateEnd)//out of time
                    {
                        if (mTomatoType.equals(WatchFaceUtil.KEY_TOMATO_WORK))
                        {
                            canvas.drawText("Long relax!", centerX, centerY-height/6, mInteractionTextPaint);
                            canvas.drawText("Cancel", centerX-width/4, centerY+height/4,mInteractionTextPaint);
                            canvas.drawText("Relax", centerX+width/4, centerY+height/4,mInteractionTextPaint);
                        }
                        else // relax , long relax
                        {
                            canvas.drawText("Start to work!", centerX, centerY-height/6, mInteractionTextPaint);
                            canvas.drawText("Cancel", centerX-width/4, centerY+height/4,mInteractionTextPaint);
                            canvas.drawText("Idle", centerX+width/4, centerY+height/4,mInteractionTextPaint);
                        }
                    }
                    else
                    {
                        if (mTomatoType.equals(WatchFaceUtil.KEY_TOMATO_WORK))
                        {
                            canvas.drawText("Reset work timer", centerX, centerY-height/6, mInteractionTextPaint);
                            canvas.drawText("Cancel", centerX-width/4, centerY+height/4,mInteractionTextPaint);
                            canvas.drawText("Idle", centerX+width/4, centerY+height/4,mInteractionTextPaint);
                        }
                        else // relax , long relax
                        {
                            canvas.drawText("Start to work!", centerX, centerY-height/6, mInteractionTextPaint);
                            canvas.drawText("Cancel", centerX-width/4, centerY+height/4,mInteractionTextPaint);
                            canvas.drawText("Idle", centerX+width/4, centerY+height/4,mInteractionTextPaint);
                        }
                    }

                }
                else if (mControlState == WatchFaceUtil.WatchControlState.WCS_TIMER) {

                    if (mDataTimerDateStart == 0 || mDataTimerDateEnd == 0)
                    {
                        mInteractionTextPaint.setTextSize(height / 16);
                        //mInteractionTextPaint.setTypeFace(Typeface.BOLD);
                        mInteractionTextPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
                        mInteractionTextPaint.setColor(0xffff4040);
                        canvas.drawText("Profile 1", centerX - width / 4, centerY - height / 6, mInteractionTextPaint);
                        canvas.drawText("Profile 2", centerX + width/4, centerY-height/6, mInteractionTextPaint);
                        canvas.drawText("Profile 3", centerX + width/4, centerY+height/6, mInteractionTextPaint);
                        canvas.drawText("Profile 4", centerX - width/4, centerY+height/6, mInteractionTextPaint);
                        float lineHeight = mInteractionTextPaint.descent() - mInteractionTextPaint.ascent();
                        mInteractionTextPaint.setTextSize(height / 10);
                        mInteractionTextPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));
                        mInteractionTextPaint.setColor(Color.WHITE);
                        canvas.drawText(""+(mDataTimer1/60) + " min", centerX - width/4, centerY-height/6+lineHeight, mInteractionTextPaint);
                        canvas.drawText(""+(mDataTimer2/60) + " min", centerX + width/4, centerY-height/6+lineHeight, mInteractionTextPaint);
                        canvas.drawText(""+(mDataTimer3/60) + " min", centerX + width/4, centerY+height/6+lineHeight, mInteractionTextPaint);
                        canvas.drawText(""+(mDataTimer4/60) + " min", centerX - width/4, centerY+height/6+lineHeight, mInteractionTextPaint);
                        //canvas.drawText("Cancel", centerX - width/4, centerY+height/6+lineHeight/2, mInteractionTextPaint);
                    }
                    else
                    {
                        mInteractionTextPaint.setColor(Color.WHITE);
                        mInteractionTextPaint.setTextSize(height/10);
                        canvas.drawText("Idle", centerX, centerY - height / 6, mInteractionTextPaint);
                        canvas.drawText("Cancel", centerX-width/4, centerY+height/4,mInteractionTextPaint);
                        canvas.drawText("Reset", centerX+width/4, centerY+height/4,mInteractionTextPaint);
                    }
                }
            }


        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                mGoogleApiClient.connect();

                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                //mCurrentCalender.setTimeZone(TimeZone.getDefault());
                //mTime.clear(TimeZone.getDefault().getID());
                //mTime.setToNow();
            } else {
                unregisterReceiver();

                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            MainWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);

        IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(WatchFaceUtil.RECEIVER_ACTION_WATCH_FACE);
        MainWatchFace.this.registerReceiver(mServiceMsgReceiver, intentFilter);
    }

    private void unregisterReceiver() {
        if (!mRegisteredTimeZoneReceiver) {
            return;
        }
        mRegisteredTimeZoneReceiver = false;
        MainWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        MainWatchFace.this.unregisterReceiver(mServiceMsgReceiver);
    }

    private void updateCountDown()
        {
            //Log.d(TAG,"updateCountDown");
            long curMs = System.currentTimeMillis();
            long mTimerMSPre = mTimerMS;
            mTimerMS = mDataTimerDateEnd == 0?Long.MAX_VALUE: mDataTimerDateEnd - curMs;
            long mTomatoMSPre = mTomatoMS;
            mTomatoMS = mDataTomatoDateEnd == 0?Long.MAX_VALUE: mDataTomatoDateEnd - curMs;

            //Log.d(TAG,"Update timer....");
            if (mDataTomatoDateEnd!= 0 && mTomatoMSPre>0 && mTomatoMS<=0 && mTomatoMSPre != Long.MAX_VALUE)
            {
                mUpdateFlag = DRAW_MIN|DRAW_SEC;
                mVibrator.vibrate(new long[]{10, 100, 10, 100, 10, 100}, -1);
//                new Thread(){
//                    @Override
//                    public void run()
//                    {
//                        mVibrator.vibrate(new long[]{10, 100, 10, 100, 10, 100}, -1);
//                    }
//                }.start();
            }
            else if (mDataTimerDateEnd !=0 && mTimerMSPre>0 && mTimerMS<=0 && mTimerMSPre != Long.MAX_VALUE)
            {
                mUpdateFlag = DRAW_MIN|DRAW_SEC;
                mVibrator.vibrate(500);
//                new Thread(){
//                    @Override
//                    public void run()
//                    {
//                        mVibrator.vibrate(500);
//                    }
//                }.start();
            }
        }
        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
            else {
                updateCountDown();
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            updateCountDown();
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = mInteractiveUpdateRateMs
                        - (timeMs % mInteractiveUpdateRateMs);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        private void updateConfigDataItemAndUiOnStartup() {
            WatchFaceUtil.fetchConfigDataMap(mGoogleApiClient,
                    new WatchFaceUtil.FetchConfigDataMapCallback() {
                        @Override
                        public void onConfigDataMapFetched(DataMap startupConfig) {
                            // If the DataItem hasn't been created yet or some keys are missing,
                            // use the default values.
                            setDefaultValuesForMissingConfigKeys(startupConfig);
                            WatchFaceUtil.putConfigDataItem(mGoogleApiClient, startupConfig);

                            updateUiForConfigDataMap(startupConfig);
                        }
                    }
            );
        }


        private void setDefaultValuesForMissingConfigKeys(DataMap config) {
            addIntKeyIfMissingInt(config, WatchFaceUtil.KEY_TIMER1,
                    WatchFaceUtil.DEFAULT_TIMER1);
            addIntKeyIfMissingInt(config, WatchFaceUtil.KEY_TIMER2,
                    WatchFaceUtil.DEFAULT_TIMER2);
            addIntKeyIfMissingInt(config, WatchFaceUtil.KEY_TIMER3,
                    WatchFaceUtil.DEFAULT_TIMER3);
            addIntKeyIfMissingInt(config, WatchFaceUtil.KEY_TIMER4,
                    WatchFaceUtil.DEFAULT_TIMER4);

            addIntKeyIfMissingLong(config, WatchFaceUtil.KEY_TIMER_DATE_START,
                    WatchFaceUtil.DEFAULT_TIMER_ZERO);
            addIntKeyIfMissingLong(config, WatchFaceUtil.KEY_TIMER_DATE_END,
                    WatchFaceUtil.DEFAULT_TIMER_ZERO);

            addIntKeyIfMissingInt(config, WatchFaceUtil.KEY_TOMATO_WORK,
                    WatchFaceUtil.DEFAULT_TOMATO_WORK);
            addIntKeyIfMissingInt(config, WatchFaceUtil.KEY_TOMATO_RELAX,
                    WatchFaceUtil.DEFAULT_TOMATO_RELAX);
            addIntKeyIfMissingInt(config, WatchFaceUtil.KEY_TOMATO_RELAX_LONG,
                    WatchFaceUtil.DEFAULT_TOMATO_RELAX_LONG);

            addIntKeyIfMissingLong(config, WatchFaceUtil.KEY_TOMATO_CALENDAR_ID,
                    WatchFaceUtil.DEFAULT_TOMATO_CALENDAR_ID);
            addIntKeyIfMissingString(config, WatchFaceUtil.KEY_TOMATO_CALENDAR_NAME,
                    WatchFaceUtil.DEFAULT_TOMATO_CALENDAR_NAME);
            addIntKeyIfMissingInt(config, WatchFaceUtil.KEY_TOMATO_CALENDAR_COLOR,
                    WatchFaceUtil.DEFAULT_TOMATO_CALENDAR_COLOR);
            addIntKeyIfMissingString(config, WatchFaceUtil.KEY_TOMATO_CALENDAR_ACCOUNT_NAME,
                    WatchFaceUtil.DEFAULT_TOMATO_CALENDAR_ACCOUNT_NAME);

            addIntKeyIfMissingStringArray(config, WatchFaceUtil.KEY_TOMATO_TAGS,
                    WatchFaceUtil.DEFAULT_TOMATO_TAGS);
            addIntKeyIfMissingDataMapArray(config, WatchFaceUtil.KEY_TOMATO_EVENT_QUEUE, null);


            addIntKeyIfMissingString(config, WatchFaceUtil.KEY_TOMATO_TYPE,
                    WatchFaceUtil.DEFAULT_TOMATO_TYPE);

            addIntKeyIfMissingLong(config, WatchFaceUtil.KEY_TOMATO_DATE_START,
                    WatchFaceUtil.DEFAULT_TIMER_ZERO);
            addIntKeyIfMissingLong(config, WatchFaceUtil.KEY_TOMATO_DATE_END,
                    WatchFaceUtil.DEFAULT_TIMER_ZERO);
            //KEY_TOMATO_TYPE
        }

        private void addIntKeyIfMissingInt(DataMap config, String key, int sec) {
            if (!config.containsKey(key)) {
                Log.d(TAG, "addIntKeyIfMissing  key: " + key + "  sec: " + sec);
                config.putInt(key, sec);
            }
        }

        private void addIntKeyIfMissingLong(DataMap config, String key, long value) {
            if (!config.containsKey(key)) {
                Log.d(TAG,"addIntKeyIfMissing  key: " + key + "  value: " + value);
                config.putLong(key, value);
            }
        }

        private void addIntKeyIfMissingString(DataMap config, String key, String str) {
            if (!config.containsKey(key)) {
                Log.d(TAG,"addIntKeyIfMissing  key: " + key + "  str: " + str);
                config.putString(key, str);
            }
        }

        private void addIntKeyIfMissingStringArray(DataMap config, String key, String[] array) {
            if (!config.containsKey(key)) {
                Log.d(TAG,"addIntKeyIfMissing  key: " + key + "  str: " + array);
                config.putStringArray(key, array);
            }
        }

        private void addIntKeyIfMissingDataMapArray(DataMap config, String key, ArrayList<DataMap> array) {
            if (!config.containsKey(key)) {
                Log.d(TAG, "addIntKeyIfMissing  key: " + key);
                if (array == null)
                    array = new ArrayList<DataMap>();
                config.putDataMapArrayList(key, array);
            }
        }

        @Override // DataApi.DataListener
        public void onDataChanged(DataEventBuffer dataEvents) {
            for (DataEvent dataEvent : dataEvents) {
                if (dataEvent.getType() != DataEvent.TYPE_CHANGED) {
                    continue;
                }

                DataItem dataItem = dataEvent.getDataItem();
                if (!dataItem.getUri().getPath().equals(
                        WatchFaceUtil.PATH_WITH_FEATURE)) {
                    continue;
                }

                DataMapItem dataMapItem = DataMapItem.fromDataItem(dataItem);
                DataMap config = dataMapItem.getDataMap();
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Config DataItem updated:" + config);
                }
                //Log.d(TAG,"onDataChanged");
                updateUiForConfigDataMap(config);
            }
        }

        private void updateUiForConfigDataMap(final DataMap config) {
            boolean uiUpdated = false;
            for (String configKey : config.keySet()) {
                if (!config.containsKey(configKey)) {
                    continue;
                }

                int newTime = -1;

                if (configKey.equals(WatchFaceUtil.KEY_TIMER1)) {
                    newTime = mDataTimer1 = config.getInt(configKey);uiUpdated = true;
                } else if (configKey.equals(WatchFaceUtil.KEY_TIMER2)) {
                    newTime =  mDataTimer2 = config.getInt(configKey);uiUpdated = true;
                } else if (configKey.equals(WatchFaceUtil.KEY_TIMER3)) {
                    newTime = mDataTimer3 = config.getInt(configKey);uiUpdated = true;
                } else if (configKey.equals(WatchFaceUtil.KEY_TIMER4)) {
                    newTime = mDataTimer4 = config.getInt(configKey);uiUpdated = true;
                } else if (configKey.equals(WatchFaceUtil.KEY_TIMER_DATE_START)) {
                    mDataTimerDateStart = config.getLong(configKey);uiUpdated = true;
                } else if (configKey.equals(WatchFaceUtil.KEY_TIMER_DATE_END)) {
                    mDataTimerDateEnd = config.getLong(configKey);uiUpdated = true;
                } else if (configKey.equals(WatchFaceUtil.KEY_TOMATO_WORK)) {
                    newTime =  mDataTomatoWork = config.getInt(configKey);uiUpdated = true;
                } else if (configKey.equals(WatchFaceUtil.KEY_TOMATO_RELAX)) {
                    newTime =  mDataTomatoRelax = config.getInt(configKey);uiUpdated = true;
                } else if (configKey.equals(WatchFaceUtil.KEY_TOMATO_RELAX_LONG)) {
                    newTime = mDataTomatoRelaxLong = config.getInt(configKey);uiUpdated = true;
                } else if (configKey.equals(WatchFaceUtil.KEY_TOMATO_CALENDAR_ID)) {
                    mCalendarID = config.getLong(configKey);uiUpdated = true;
                    //Log.d(TAG, "updateUiForConfigDataMap configKey:" + configKey + " mCalendarID: " + mCalendarID);
                } else if (configKey.equals(WatchFaceUtil.KEY_TOMATO_CALENDAR_COLOR)) {
                    mCalendarColor = config.getInt(configKey);uiUpdated = true;
                } else if (configKey.equals(WatchFaceUtil.KEY_TOMATO_CALENDAR_NAME)) {
                    mCalendarName = config.getString(configKey);uiUpdated = true;
                    //Log.d(TAG, "updateUiForConfigDataMap configKey:" + configKey + " mCalendarName: " + mCalendarName);
                } else if (configKey.equals(WatchFaceUtil.KEY_TOMATO_TAGS)) {
                    mTomatoTags = config.getStringArray(configKey);uiUpdated = true;
                    //Log.d(TAG, "updateUiForConfigDataMap configKey:" + configKey + " mTomatoTags: ");
                    for (String tag : mTomatoTags)
                    {
                        Log.d(TAG, "Tag:" + tag);
                    }

                }else if (configKey.equals(WatchFaceUtil.KEY_TOMATO_EVENT_QUEUE)) {
                    mTomatoEventQueue = config.getDataMapArrayList(configKey);
                    uiUpdated = true;
                    for (DataMap map : mTomatoEventQueue)
                    {
                        Log.d(TAG,map.toString());
                    }
                    Log.d(TAG,WatchFaceUtil.KEY_TOMATO_EVENT_QUEUE + " size is " + mTomatoEventQueue.size());

                    if (mTomatoEventQueue.size() != 0)
                    {
                        Calendar cal = Calendar.getInstance(TimeZone.getDefault());
                        DataMap eventMsgMap = new DataMap();
                        eventMsgMap.putLong(WatchFaceUtil.MSG_ID_KEY, System.currentTimeMillis());
                        eventMsgMap.putString(WatchFaceUtil.MSG_SENDER_KEY, WatchFaceUtil.MSG_SENDER_WATCH_FACE);
                        eventMsgMap.putInt(WatchFaceUtil.MSG_TYPE_KEY, WatchFaceUtil.MSG_TYPE_CREATE_EVENT);

                        Log.d(TAG,"Send create event msg..." + eventMsgMap.toString());
                        new SendActivityPhoneMessage(eventMsgMap).start();
                    }
                } else if (configKey.equals(WatchFaceUtil.KEY_TOMATO_TYPE)) {
                    mTomatoType = config.getString(configKey);uiUpdated = true;
                } else if (configKey.equals(WatchFaceUtil.KEY_TOMATO_DATE_START)) {
                    mDataTomatoDateStart = config.getLong(configKey);uiUpdated = true;
//                    Long dateInMillis = config.getLong(configKey);uiUpdated = true;
//                    Calendar cal = Calendar.getInstance();
//                    cal.setTimeInMillis(dateInMillis);
//                    mTomatoDate = cal.getTime();
                    uiUpdated = true;
                } else if (configKey.equals(WatchFaceUtil.KEY_TOMATO_DATE_END)) {
                    mDataTomatoDateEnd = config.getLong(configKey);uiUpdated = true;
                } else {
                    Log.w(TAG, "Ignoring unknown config key: " + configKey);
                }

                mCacheLastUpeateHour = -1;
                mCacheLastUpeateMin = -1;
                mCacheLastUpeateAmbienHour = -1;
                //mUpdateFlag = DRAW_ALL;
                //Log.d(TAG, "updateUiForConfigDataMap configKey:" + configKey + " sec: " + newTime);

            }

//            if (uiUpdated)
//            {
//                mListAdapter.notifyDataSetChanged();
//            }

        }
        @Override  // GoogleApiClient.ConnectionCallbacks
        public void onConnected(Bundle connectionHint) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onConnected: " + connectionHint);
            }
            Wearable.DataApi.addListener(mGoogleApiClient, this);
            updateConfigDataItemAndUiOnStartup();
        }

        @Override  // GoogleApiClient.ConnectionCallbacks
        public void onConnectionSuspended(int cause) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onConnectionSuspended: " + cause);
            }
        }

        @Override  // GoogleApiClient.OnConnectionFailedListener
        public void onConnectionFailed(ConnectionResult result) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onConnectionFailed: " + result);
            }
        }


        class SendActivityPhoneMessage extends Thread {
            String path;
            DataMap dataMap;

            // Constructor to send a message to the data layer
//            SendActivityPhoneMessage(String p, DataMap data) {
//                path = p;// WatchFaceUtil.PATH_WITH_FEATURE
//                dataMap = data;
//            }

            SendActivityPhoneMessage(DataMap data) {
                path = WatchFaceUtil.PATH_WITH_MESSAGE;
                dataMap = data;
            }

            SendActivityPhoneMessage(String key,int value) {
                path = WatchFaceUtil.PATH_WITH_MESSAGE;
                dataMap = new DataMap();
                dataMap.putInt(key, value);
            }

            SendActivityPhoneMessage(String key,String str) {
                path = WatchFaceUtil.PATH_WITH_MESSAGE;
                dataMap = new DataMap();
                dataMap.putString(key, str);
            }

            public void run() {
//                if (!mGoogleApiClient.isConnected())
//                    mGoogleApiClient.blockingConnect(100, TimeUnit.MILLISECONDS);
//                NodeApi.GetLocalNodeResult nodes = Wearable.NodeApi.getLocalNode(mGoogleApiClient).await();
//                Node node = nodes.getNode();
//                Log.v(TAG, "Activity Node is : "+node.getId()+ " - " + node.getDisplayName());
//                MessageApi.SendMessageResult result = Wearable.MessageApi.sendMessage(mGoogleApiClient, node.getId(), path, dataMap.toByteArray()).await();
//                if (result.getStatus().isSuccess()) {
//                    Log.v(TAG, "Wear: Activity Message: {" + dataMap.toString() + "} sent to: " + node.getDisplayName());
//                }
//                else {
//                    // Log an error
//                    Log.v(TAG, "ERROR: failed to send Activity Message");
//                }

                if (!mGoogleApiClient.isConnected())
                    mGoogleApiClient.blockingConnect(100, TimeUnit.MILLISECONDS);
                NodeApi.GetConnectedNodesResult result =
                        Wearable.NodeApi.getConnectedNodes(mGoogleApiClient).await();

                List<Node> nodes = result.getNodes();
                for (Node n : nodes)
                    Log.d(TAG,"Node " + n.getId() + "  " + n.getDisplayName());
                if (nodes.size() > 0) {
                    MessageApi.SendMessageResult sendResult = Wearable.MessageApi.sendMessage(mGoogleApiClient, nodes.get(0).getId(), path, dataMap.toByteArray()).await();
                    if (sendResult.getStatus().isSuccess()) {
                        Log.v(TAG, "Wear: Activity Message: {" + dataMap.toString() + "} sent to: " + nodes.get(0).getId());
                    } else {
                        // Log an error
                        Log.v(TAG, "Wear ERROR: failed to send Activity Message");
                    }
                }
                else
                    Log.v(TAG, "Wear ERROR: no node to send....");
            }
        }
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<MainWatchFace.Engine> mWeakReference;

        public EngineHandler(MainWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            MainWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }
}
