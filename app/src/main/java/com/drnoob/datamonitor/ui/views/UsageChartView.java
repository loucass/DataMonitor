package com.drnoob.datamonitor.ui.views;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import com.drnoob.datamonitor.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Hybrid stacked bar chart replacing the 14x ProgressView overview block.
 * Single View, zero dependencies, static Canvas rendering.
 * Units are megabytes. Auto-scale: max(500, ceil(peak * 1.2)).
 * Mobile (bottom, solid) vs wifi (top, hatched) distinguishable without color alone.
 */
public class UsageChartView extends View {

    public static class DayEntry {
        public final String dayLabel;
        public final float mobileMb;
        public final float wifiMb;

        public DayEntry(String dayLabel, float mobileMb, float wifiMb) {
            this.dayLabel = dayLabel == null ? "" : dayLabel;
            this.mobileMb = Math.max(0f, mobileMb);
            this.wifiMb = Math.max(0f, wifiMb);
        }

        public DayEntry(String dayLabel, long mobileMb, long wifiMb) {
            this(dayLabel, (float) mobileMb, (float) wifiMb);
        }

        public float totalMb() {
            return mobileMb + wifiMb;
        }
    }

    private static final int DAYS = 7;
    private static final float AUTO_MIN_MAX_MB = 500f;
    private static final float AUTO_HEADROOM = 1.2f;
    private static final float[] GRID_FRACTIONS = {0f, 0.25f, 0.5f, 0.75f, 1f};

    private static final int MOBILE_START = 0xFF3E51FF;
    private static final int MOBILE_END = 0xFF0DC5FF;
    private static final int WIFI_START = 0xFF00FF38;
    private static final int WIFI_END = 0xFF66DAFF;

    private final List<DayEntry> mData = new ArrayList<>(DAYS);
    private float mMaxMb = AUTO_MIN_MAX_MB;
    private boolean mManualMax = false;

    private float mDensity;
    private float mBarWidthPx;
    private float mMinBarPx;
    private float mDotPx;
    private float mGapPx;
    private float mGutterPx;
    private float mTopRoomPx;
    private float mBottomRoomPx;
    private float mBarAreaPx;
    private boolean mShowValueLabels = true;
    private boolean mShowDayLabels = true;

    private int mMobileStart = MOBILE_START;
    private int mMobileEnd = MOBILE_END;
    private int mWifiStart = WIFI_START;
    private int mWifiEnd = WIFI_END;
    private int mTextColor;
    private int mSubTextColor;
    private int mGridColor;

    private final Paint mMobilePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mWifiPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mHatchPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mYLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mValuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mDayLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mBarPath = new Path();
    private final Path mWifiPath = new Path();
    private final RectF mChartRect = new RectF();
    private final float[] mRadiiTop = new float[8];
    private final float[] mRadiiBottom = new float[8];
    private final float[] mRadiiFull = new float[8];

    public UsageChartView(Context context) {
        super(context);
        init(null);
    }

    public UsageChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(attrs);
    }

    public UsageChartView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(attrs);
    }

    private void init(AttributeSet attrs) {
        setFocusable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);

        mDensity = getResources().getDisplayMetrics().density;
        float scaled = getResources().getDisplayMetrics().scaledDensity;

        mBarWidthPx = 22f * mDensity;
        mMinBarPx = 12f * mDensity;
        mDotPx = 8f * mDensity;
        mGapPx = 2f * mDensity;
        mGutterPx = 36f * mDensity;
        mTopRoomPx = 18f * mDensity;
        mBottomRoomPx = 22f * mDensity;
        mBarAreaPx = 180f * mDensity;
        float valueTs = 7f * scaled;
        float yTs = 9f * scaled;
        float dayTs = 10f * scaled;

        if (attrs != null) {
            TypedArray a = getContext().obtainStyledAttributes(attrs, R.styleable.UsageChartView);
            try {
                mBarWidthPx = a.getDimension(R.styleable.UsageChartView_ucv_barWidth, mBarWidthPx);
                mMinBarPx = a.getDimension(R.styleable.UsageChartView_ucv_minBarHeight, mMinBarPx);
                mDotPx = a.getDimension(R.styleable.UsageChartView_ucv_dotDiameter, mDotPx);
                valueTs = a.getDimension(R.styleable.UsageChartView_ucv_valueTextSize, valueTs);
                mShowValueLabels = a.getBoolean(R.styleable.UsageChartView_ucv_showValueLabels, true);
                mShowDayLabels = a.getBoolean(R.styleable.UsageChartView_ucv_showDayLabels, true);
                mMobileStart = a.getColor(R.styleable.UsageChartView_ucv_mobileStart, MOBILE_START);
                mMobileEnd = a.getColor(R.styleable.UsageChartView_ucv_mobileEnd, MOBILE_END);
                mWifiStart = a.getColor(R.styleable.UsageChartView_ucv_wifiStart, WIFI_START);
                mWifiEnd = a.getColor(R.styleable.UsageChartView_ucv_wifiEnd, WIFI_END);
            } finally {
                a.recycle();
            }
        }

        float r = mBarWidthPx / 2f;
        setRadii(mRadiiTop, r, r, 0f, 0f);
        setRadii(mRadiiBottom, 0f, 0f, r, r);
        setRadii(mRadiiFull, r, r, r, r);

        mMobilePaint.setStyle(Paint.Style.FILL);
        mWifiPaint.setStyle(Paint.Style.FILL);
        mDotPaint.setStyle(Paint.Style.FILL);

        mHatchPaint.setStyle(Paint.Style.STROKE);
        mHatchPaint.setStrokeWidth(Math.max(1f, 1.5f * mDensity));
        mHatchPaint.setColor(Color.WHITE);
        mHatchPaint.setAlpha(70);

        mGridPaint.setStyle(Paint.Style.STROKE);
        mGridPaint.setStrokeWidth(Math.max(1f, mDensity));

        mYLabelPaint.setTextAlign(Paint.Align.RIGHT);
        mYLabelPaint.setTextSize(yTs);
        mValuePaint.setTextAlign(Paint.Align.CENTER);
        mValuePaint.setTextSize(valueTs);
        mDayLabelPaint.setTextAlign(Paint.Align.CENTER);
        mDayLabelPaint.setTextSize(dayTs);

        resolveThemeColors();
        for (int i = 0; i < DAYS; i++) {
            mData.add(new DayEntry("", 0f, 0f));
        }
        updateContentDescription();
    }

    private static void setRadii(float[] out, float top, float topR, float bottom, float bottomR) {
        out[0] = top;
        out[1] = top;
        out[2] = topR;
        out[3] = topR;
        out[4] = bottom;
        out[5] = bottom;
        out[6] = bottomR;
        out[7] = bottomR;
    }

    private void resolveThemeColors() {
        boolean dark = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        mTextColor = resolveThemeColor(android.R.attr.textColorPrimary, dark ? 0xFFE5F1FF : 0xFF001733);
        mSubTextColor = resolveThemeColor(android.R.attr.textColorSecondary, dark ? 0xFF788491 : 0xFF6E7987);
        mGridColor = dark ? 0xFF5C5C5C : 0xFFE6E6E6;
        mYLabelPaint.setColor(mSubTextColor);
        mValuePaint.setColor(mTextColor);
        mDayLabelPaint.setColor(mSubTextColor);
        mGridPaint.setColor(mGridColor);
        mDotPaint.setColor(mGridColor);
    }

    private int resolveThemeColor(int attr, int fallback) {
        TypedValue tv = new TypedValue();
        if (getContext().getTheme().resolveAttribute(attr, tv, true)
                && tv.type >= TypedValue.TYPE_FIRST_COLOR_INT
                && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
            return tv.data;
        }
        return fallback;
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        resolveThemeColors();
        invalidate();
    }

    public void setData(List<DayEntry> data) {
        mData.clear();
        if (data != null) {
            for (int i = 0; i < data.size() && mData.size() < DAYS; i++) {
                if (data.get(i) != null) {
                    mData.add(data.get(i));
                }
            }
        }
        while (mData.size() < DAYS) {
            mData.add(new DayEntry("", 0f, 0f));
        }
        if (!mManualMax) {
            mMaxMb = computeAutoMax();
        }
        updateContentDescription();
        invalidate();
    }

    public void setMaxMb(float maxMb) {
        if (maxMb <= 0f) {
            mManualMax = false;
            mMaxMb = computeAutoMax();
        } else {
            mManualMax = true;
            mMaxMb = maxMb;
        }
        updateContentDescription();
        invalidate();
    }

    public float getMaxMb() {
        return mMaxMb;
    }

    public List<DayEntry> getData() {
        return new ArrayList<>(mData);
    }

    private float computeAutoMax() {
        float peak = 0f;
        for (int i = 0; i < mData.size(); i++) {
            peak = Math.max(peak, mData.get(i).totalMb());
        }
        return Math.max(AUTO_MIN_MAX_MB, (float) Math.ceil(peak * AUTO_HEADROOM));
    }

    private void updateContentDescription() {
        StringBuilder sb = new StringBuilder(256);
        for (int i = 0; i < mData.size(); i++) {
            DayEntry d = mData.get(i);
            String day = d.dayLabel.isEmpty() ? ("Day " + (i + 1)) : d.dayLabel;
            sb.append(day)
                    .append(" mobile ").append(formatAxis(d.mobileMb))
                    .append(" wifi ").append(formatAxis(d.wifiMb));
            if (i < mData.size() - 1) {
                sb.append("; ");
            }
        }
        setContentDescription(sb.toString());
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desiredW = (int) (mGutterPx + DAYS * (mBarWidthPx + 16f * mDensity)
                + getPaddingLeft() + getPaddingRight());
        int desiredH = (int) (mTopRoomPx + mBarAreaPx + mBottomRoomPx
                + getPaddingTop() + getPaddingBottom());
        setMeasuredDimension(resolveSize(desiredW, widthMeasureSpec),
                resolveSize(desiredH, heightMeasureSpec));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        computeChartRect(mChartRect);
        if (mChartRect.height() > 0) {
            mMobilePaint.setShader(new LinearGradient(0, mChartRect.bottom, 0, mChartRect.top,
                    mMobileStart, mMobileEnd, Shader.TileMode.CLAMP));
            mWifiPaint.setShader(new LinearGradient(0, mChartRect.bottom, 0, mChartRect.top,
                    mWifiStart, mWifiEnd, Shader.TileMode.CLAMP));
        }
    }

    private void computeChartRect(RectF out) {
        out.set(getPaddingLeft() + mGutterPx,
                getPaddingTop() + mTopRoomPx,
                getWidth() - getPaddingRight() - 4f * mDensity,
                getHeight() - getPaddingBottom() - mBottomRoomPx);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        computeChartRect(mChartRect);
        float chartH = mChartRect.height();
        if (chartH <= 0 || mChartRect.width() <= 0 || mMaxMb <= 0) {
            return;
        }
        for (int g = 0; g < GRID_FRACTIONS.length; g++) {
            float y = mChartRect.bottom - GRID_FRACTIONS[g] * chartH;
            canvas.drawLine(mChartRect.left, y, mChartRect.right, y, mGridPaint);
            canvas.drawText(formatAxis(mMaxMb * GRID_FRACTIONS[g]),
                    mChartRect.left - 4f * mDensity,
                    y + mYLabelPaint.getTextSize() * 0.35f, mYLabelPaint);
        }
        float slotW = mChartRect.width() / DAYS;
        for (int i = 0; i < DAYS; i++) {
            float cx = mChartRect.left + slotW * i + slotW / 2f;
            drawDay(canvas, mData.get(i), cx);
            if (mShowDayLabels && !mData.get(i).dayLabel.isEmpty()) {
                canvas.drawText(mData.get(i).dayLabel, cx,
                        mChartRect.bottom + mDayLabelPaint.getTextSize() + 4f * mDensity,
                        mDayLabelPaint);
            }
        }
    }

    private void drawDay(Canvas canvas, DayEntry d, float cx) {
        float half = mBarWidthPx / 2f;
        float left = cx - half;
        float right = cx + half;
        float chartH = mChartRect.height();

        if (d.totalMb() < 1f) {
            float r = mDotPx / 2f;
            float cy = mChartRect.bottom - r;
            if (d.mobileMb > 0) {
                mDotPaint.setColor(mMobileEnd);
            } else if (d.wifiMb > 0) {
                mDotPaint.setColor(mWifiEnd);
            } else {
                mDotPaint.setColor(mGridColor);
            }
            canvas.drawCircle(cx, cy, r, mDotPaint);
            if (mShowValueLabels) {
                canvas.drawText(formatAxis(d.totalMb()), cx, cy - r - 4f * mDensity, mValuePaint);
            }
            return;
        }

        boolean hasMobile = d.mobileMb > 0f;
        boolean hasWifi = d.wifiMb > 0f;
        float mobileH = hasMobile ? Math.max(mMinBarPx, chartH * (d.mobileMb / mMaxMb)) : 0f;
        float wifiH = hasWifi ? Math.max(mMinBarPx, chartH * (d.wifiMb / mMaxMb)) : 0f;

        float mobileBottom = mChartRect.bottom;
        float mobileTop = mobileBottom - mobileH;
        float wifiBottom = mobileTop - ((hasMobile && hasWifi) ? mGapPx : 0f);
        float wifiTop = Math.max(mChartRect.top, wifiBottom - wifiH);
        if (!hasWifi) {
            mobileTop = Math.max(mChartRect.top, mobileTop);
        }

        if (hasMobile && hasWifi) {
            mBarPath.rewind();
            mBarPath.addRoundRect(left, mobileTop, right, mobileBottom,
                    mRadiiBottom, Path.Direction.CW);
            canvas.drawPath(mBarPath, mMobilePaint);

            mWifiPath.rewind();
            mWifiPath.addRoundRect(left, wifiTop, right, wifiBottom,
                    mRadiiTop, Path.Direction.CW);
            canvas.drawPath(mWifiPath, mWifiPaint);
            drawHatch(canvas, left, right, wifiTop, wifiBottom);
        } else if (hasMobile) {
            mBarPath.rewind();
            mBarPath.addRoundRect(left, mobileTop, right, mobileBottom,
                    mRadiiFull, Path.Direction.CW);
            canvas.drawPath(mBarPath, mMobilePaint);
        } else {
            mWifiPath.rewind();
            mWifiPath.addRoundRect(left, wifiTop, right, wifiBottom,
                    mRadiiFull, Path.Direction.CW);
            canvas.drawPath(mWifiPath, mWifiPaint);
            drawHatch(canvas, left, right, wifiTop, wifiBottom);
        }

        if (mShowValueLabels) {
            float barTop = hasWifi ? wifiTop : mobileTop;
            float y = Math.max(barTop - 4f * mDensity,
                    getPaddingTop() + mValuePaint.getTextSize());
            canvas.drawText(formatAxis(d.totalMb()), cx, y, mValuePaint);
        }
    }

    private void drawHatch(Canvas canvas, float left, float right, float top, float bottom) {
        float h = bottom - top;
        if (h <= 0) {
            return;
        }
        canvas.save();
        canvas.clipPath(mWifiPath);
        float spacing = 7f * mDensity;
        for (float x = left - h; x < right; x += spacing) {
            canvas.drawLine(x, bottom, x + h, top, mHatchPaint);
        }
        canvas.restore();
    }

    private static String formatAxis(float mb) {
        if (mb < 1f) {
            return String.format(Locale.US, "%.1f MB", mb);
        }
        if (mb < 1000f) {
            if (mb >= 100f || mb == (float) (int) mb) {
                return ((int) mb) + " MB";
            }
            return String.format(Locale.US, "%.1f MB", mb);
        }
        float gb = mb / 1024f;
        if (gb >= 10f || gb == (float) (int) gb) {
            return ((int) gb) + " GB";
        }
        return String.format(Locale.US, "%.1f GB", gb);
    }
}
