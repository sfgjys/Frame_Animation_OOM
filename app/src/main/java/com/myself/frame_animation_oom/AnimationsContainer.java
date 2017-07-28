package com.myself.frame_animation_oom;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.os.Build;
import android.os.Handler;
import android.widget.ImageView;

import java.lang.ref.SoftReference;

/**
 * 解决思路
 * 先分析下普通方法为啥会OOM，从xml中读取到图片id列表后就去硬盘中找这些图片资源，将图片全部读出来后按顺序设置给ImageView，
 * 利用视觉暂留效果实现了动画。一次拿出这么多图片，而系统都是以Bitmap位图形式读取的（作为OOM的常客，这锅Bitmap来背）；
 * 而动画的播放是按顺序来的，大量Bitmap就排好队等待播放然后释放，然而这个排队的地方只有10平米，呵呵~发现问题了吧。
 * 按照大神的思路，既然来这么多Bitmap，一次却只能临幸一个，那么就翻牌子吧，轮到谁就派个线程去叫谁，bitmap1叫到了得叫上下一位bitmap2做准备，
 * 这样更迭效率高一些。为了避免某个bitmap已被叫走了线程白跑一趟的情况，加个Synchronized同步下数据信息，实现代码如下
 */

public class AnimationsContainer {
    public int FPS = 25;  // 每秒播放帧数，fps = 1/t，t-动画两帧时间间隔
    private int resId = R.array.loading_anim; //图片资源
    private Context mContext = MyApplication.getAppContext();
    // 单例
    private static AnimationsContainer mInstance;


    public AnimationsContainer() {
    }

    //获取单例
    public static AnimationsContainer getInstance(int resId, int fps) {
        if (mInstance == null)
            mInstance = new AnimationsContainer();
        return mInstance;
    }

    public void setResId(int resId) {
        this.resId = resId;
    }

    // 从xml中读取资源ID数组
    private int[] mProgressAnimFrames = getData(resId);

    /**
     * @param imageView
     * @return progress dialog animation
     */
    public FramesSequenceAnimation createProgressDialogAnim(ImageView imageView) {
        return new FramesSequenceAnimation(imageView, mProgressAnimFrames, FPS);
    }


    /**
     * 循环读取帧---循环播放帧
     */
    public class FramesSequenceAnimation {
        private int[] mFrames; // 帧数组
        private int mIndex; // 当前帧
        private boolean mShouldRun; // 开始/停止播放用
        private boolean mIsRunning; // 动画是否正在播放，防止重复播放
        private SoftReference<ImageView> mSoftReferenceImageView; // 软引用ImageView，以便及时释放掉
        private Handler mHandler;
        private int mDelayMillis;
        private OnAnimationStoppedListener mOnAnimationStoppedListener; //播放停止监听

        private Bitmap mBitmap = null;
        private BitmapFactory.Options mBitmapOptions;//Bitmap管理类，可有效减少Bitmap的OOM问题

        public FramesSequenceAnimation(ImageView imageView, int[] frames, int fps) {
            mHandler = new Handler();

            mFrames = frames;
            mDelayMillis = 1000 / fps;//帧动画时间间隔，毫秒

            mIndex = -1;
            mSoftReferenceImageView = new SoftReference<ImageView>(imageView);
            mShouldRun = false;
            mIsRunning = false;

            imageView.setImageResource(mFrames[0]);

            // 当图片大小类型相同时进行复用，避免频繁GC
            if (Build.VERSION.SDK_INT >= 11) {// 版本好大于11
                Bitmap bmp = ((BitmapDrawable) imageView.getDrawable()).getBitmap();
                int width = bmp.getWidth();
                int height = bmp.getHeight();
                Bitmap.Config config = bmp.getConfig();
                mBitmap = Bitmap.createBitmap(width, height, config);
                mBitmapOptions = new BitmapFactory.Options();
                //设置Bitmap内存复用
                mBitmapOptions.inBitmap = mBitmap;//Bitmap复用内存块，类似对象池，避免不必要的内存分配和回收
                mBitmapOptions.inMutable = true;//解码时返回可变Bitmap
                mBitmapOptions.inSampleSize = 1;//缩放比例
            }
        }

        //循环获取下一帧图片的资源id
        private int getNext() {
            mIndex++;
            if (mIndex >= mFrames.length)
                mIndex = 0;
            return mFrames[mIndex];
        }

        /**
         * 播放动画，同步锁防止多线程读帧时，数据安全问题
         */
        public synchronized void start() {
            mShouldRun = true;
            if (mIsRunning)
                return;
            Runnable runnable = new Runnable() {
                @Override
                public void run() {
                    ImageView imageView = mSoftReferenceImageView.get();
                    if (!mShouldRun || imageView == null) {// 循环播放时到此时停止了播放，调用了停止播放的监听
                        mIsRunning = false;
                        if (mOnAnimationStoppedListener != null) {
                            mOnAnimationStoppedListener.AnimationStopped();
                        }
                        return;
                    }

                    mIsRunning = true;
                    // 新开线程去读下一帧
                    mHandler.postDelayed(this, mDelayMillis);// 延时mDelayMillis后开始

                    if (imageView.isShown()) {
                        int imageRes = getNext();
                        if (mBitmap != null) { // so Build.VERSION.SDK_INT >= 11
                            Bitmap bitmap = null;
                            try {
                                bitmap = BitmapFactory.decodeResource(imageView.getResources(), imageRes, mBitmapOptions);
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                            if (bitmap != null) {
                                imageView.setImageBitmap(bitmap);
                            } else {
                                imageView.setImageResource(imageRes);
                                mBitmap.recycle();
                                mBitmap = null;
                            }
                        } else {// 版本号低于11
                            imageView.setImageResource(imageRes);
                        }
                    }
                }
            };

            mHandler.post(runnable);
        }

        /**
         * 停止播放
         */
        public synchronized void stop() {
            mShouldRun = false;
        }

        /**
         * 设置停止播放监听
         *
         * @param listener
         */
        public void setOnAnimStopListener(OnAnimationStoppedListener listener) {
            this.mOnAnimationStoppedListener = listener;
        }
    }

    /**
     * 从xml中读取帧数组
     *
     * @param resId
     * @return
     */
    private int[] getData(int resId) {
        TypedArray array = mContext.getResources().obtainTypedArray(resId);

        int len = array.length();
        int[] intArray = new int[array.length()];

        for (int i = 0; i < len; i++) {
            intArray[i] = array.getResourceId(i, 0);
        }
        array.recycle();
        return intArray;
    }

    /**
     * 停止播放监听
     */
    public interface OnAnimationStoppedListener {
        void AnimationStopped();
    }
}