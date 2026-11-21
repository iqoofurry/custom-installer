package com.iqoofurry.sheetinstaller;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

/**
 * Контейнер, который раскладывает детей в строку, переносит на новую,
 * когда они не помещаются, и центрирует каждую строку по горизонтали.
 * Нужен для чипов ABI.
 */
public class FlowLayout extends ViewGroup {

    public FlowLayout(Context context) {
        this(context, null);
    }

    public FlowLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int available = Math.max(0, width - getPaddingLeft() - getPaddingRight());
        int lineWidth = 0;
        int lineHeight = 0;
        int totalHeight = 0;
        int maxLineWidth = 0;

        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) {
                continue;
            }
            measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0);
            MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();
            int childWidth = child.getMeasuredWidth() + lp.leftMargin + lp.rightMargin;
            int childHeight = child.getMeasuredHeight() + lp.topMargin + lp.bottomMargin;

            if (lineWidth > 0 && lineWidth + childWidth > available) {
                totalHeight += lineHeight;
                maxLineWidth = Math.max(maxLineWidth, lineWidth);
                lineWidth = childWidth;
                lineHeight = childHeight;
            } else {
                lineWidth += childWidth;
                lineHeight = Math.max(lineHeight, childHeight);
            }
        }
        totalHeight += lineHeight;
        maxLineWidth = Math.max(maxLineWidth, lineWidth);

        int measuredWidth = maxLineWidth + getPaddingLeft() + getPaddingRight();
        int measuredHeight = totalHeight + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(
                resolveSize(Math.max(measuredWidth, getSuggestedMinimumWidth()), widthMeasureSpec),
                resolveSize(Math.max(measuredHeight, getSuggestedMinimumHeight()), heightMeasureSpec));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int available = Math.max(0, getWidth() - getPaddingLeft() - getPaddingRight());
        int count = getChildCount();
        int y = getPaddingTop();
        int i = 0;

        while (i < count) {
            // Собираем одну строку: [i, end).
            int lineWidth = 0;
            int lineHeight = 0;
            int end = i;
            for (; end < count; end++) {
                View child = getChildAt(end);
                if (child.getVisibility() == GONE) {
                    continue;
                }
                MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();
                int childWidth = child.getMeasuredWidth() + lp.leftMargin + lp.rightMargin;
                int childHeight = child.getMeasuredHeight() + lp.topMargin + lp.bottomMargin;
                if (lineWidth > 0 && lineWidth + childWidth > available) {
                    break;
                }
                lineWidth += childWidth;
                lineHeight = Math.max(lineHeight, childHeight);
            }

            int x = getPaddingLeft() + Math.max(0, (available - lineWidth) / 2);
            for (int k = i; k < end; k++) {
                View child = getChildAt(k);
                if (child.getVisibility() == GONE) {
                    continue;
                }
                MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();
                child.layout(x + lp.leftMargin, y + lp.topMargin,
                        x + lp.leftMargin + child.getMeasuredWidth(),
                        y + lp.topMargin + child.getMeasuredHeight());
                x += child.getMeasuredWidth() + lp.leftMargin + lp.rightMargin;
            }

            y += lineHeight;
            i = end;
        }
    }

    @Override
    public LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new MarginLayoutParams(getContext(), attrs);
    }

    @Override
    protected LayoutParams generateDefaultLayoutParams() {
        return new MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
    }

    @Override
    protected LayoutParams generateLayoutParams(LayoutParams params) {
        return new MarginLayoutParams(params);
    }

    @Override
    protected boolean checkLayoutParams(LayoutParams params) {
        return params instanceof MarginLayoutParams;
    }
}