/*
 * Copyright (C) 2008 Esmertec AG.
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.mms.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.Message;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewStub;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;

import com.android.mms.R;
import com.android.mms.data.WorkingMessage;
import com.android.mms.model.SlideModel;
import com.android.mms.model.SlideshowModel;

/**
 * This is an embedded editor/view to add photos and sound/video clips
 * into a multimedia message.
 */
public class AttachmentEditor extends LinearLayout {
    private static final String TAG = "AttachmentEditor";

    static final int MSG_EDIT_SLIDESHOW   = 1;
    static final int MSG_SEND_SLIDESHOW   = 2;
    static final int MSG_PLAY_SLIDESHOW   = 3;
    static final int MSG_REPLACE_IMAGE    = 4;
    static final int MSG_REPLACE_VIDEO    = 5;
    static final int MSG_REPLACE_AUDIO    = 6;
    static final int MSG_PLAY_VIDEO       = 7;
    static final int MSG_PLAY_AUDIO       = 8;
    static final int MSG_VIEW_IMAGE       = 9;
    static final int MSG_REMOVE_ATTACHMENT = 10;

    private final Context mContext;
    private Handler mHandler;

    private SlideViewInterface mView;
    private SlideshowModel mSlideshow;
    private Presenter mPresenter;
    private boolean mCanSend;
    private boolean mCanEdit;
    private Button mSendButton;
    private Button mEditButton;

    private Button mReplaceButton;
    private Button mRemoveButton;
    private Button mViewButton;
    private ImageButton mPlayBtn;

    public AttachmentEditor(Context context, AttributeSet attr) {
        super(context, attr);
        mContext = context;
    }

    /**
     * Returns true if the attachment editor has an attachment to show.
     */
    public boolean update(WorkingMessage msg) {
        hideView();
        mView = null;

        // If there's no attachment, we have nothing to do.
        if (!msg.hasAttachment()) {
            return false;
        }

        // Get the slideshow from the message.
        mSlideshow = msg.getSlideshow();

        mView = createView();

        if ((mPresenter == null) || !mSlideshow.equals(mPresenter.getModel())) {
            mPresenter = PresenterFactory.getPresenter(
                    "MmsThumbnailPresenter", mContext, mView, mSlideshow);
        } else {
            mPresenter.setView(mView);
        }

        mPresenter.present(null);
        return true;
    }

    public void setHandler(Handler handler) {
        mHandler = handler;
    }

    public void setCanSend(boolean enable) {
        if (mCanSend != enable) {
            mCanSend = enable;
            updateSendButton();
        }
    }

    private void updateSendButton() {
        if (null != mSendButton) {
            mSendButton.setEnabled(mCanSend);
            mSendButton.setFocusable(mCanSend);
        }
    }

    public void hideView() {
        if (mView != null) {
            ((View)mView).setVisibility(View.GONE);
        }
    }

    private View getStubView(int stubId, int viewId) {
        View view = findViewById(viewId);
        if (view == null) {
            ViewStub stub = (ViewStub) findViewById(stubId);
            view = stub.inflate();
        }

        return view;
    }

    private class MessageOnClick implements OnClickListener {
        private int mWhat;

        public MessageOnClick(int what) {
            mWhat = what;
        }

        public void onClick(View v) {
            Message msg = Message.obtain(mHandler, mWhat);
            msg.sendToTarget();
            if (mWhat != MSG_REPLACE_IMAGE
                    && mWhat != MSG_REPLACE_VIDEO
                    && mWhat != MSG_REPLACE_AUDIO) {
                updateButtonsState(false);
            }
        }
    }

    private void updateButtonState(Button button, boolean flag) {
        if (button != null) {
            button.setEnabled(flag);
            button.setFocusable(flag);
        }
    }

    private void updateButtonState(ImageButton button, boolean flag) {
        if (button != null) {
            button.setEnabled(flag);
            button.setFocusable(flag);
        }
    }

    public void updateButtonsState(boolean flag) {
        if (mSlideshow != null && mSlideshow.size() > 1) {
            updateButtonState(mPlayBtn, flag);
            updateButtonState(mRemoveButton, flag);
            updateButtonState(mEditButton, flag);
        } else {
            updateButtonState(mViewButton, flag);
            updateButtonState(mReplaceButton, flag);
            updateButtonState(mRemoveButton, flag);
        }
    }

    private SlideViewInterface createView() {
        boolean inPortrait = inPortraitMode();
        if (mSlideshow.size() > 1) {
            return createSlideshowView(inPortrait);
        }

        SlideModel slide = mSlideshow.get(0);
        if (slide.hasImage()) {
            return createMediaView(
                    R.id.image_attachment_view_stub,
                    R.id.image_attachment_view,
                    R.id.view_image_button, R.id.replace_image_button, R.id.remove_image_button,
                    MSG_VIEW_IMAGE, MSG_REPLACE_IMAGE, MSG_REMOVE_ATTACHMENT);
        } else if (slide.hasVideo()) {
            return createMediaView(
                    R.id.video_attachment_view_stub,
                    R.id.video_attachment_view,
                    R.id.view_video_button, R.id.replace_video_button, R.id.remove_video_button,
                    MSG_PLAY_VIDEO, MSG_REPLACE_VIDEO, MSG_REMOVE_ATTACHMENT);
        } else if (slide.hasAudio()) {
            return createMediaView(
                    R.id.audio_attachment_view_stub,
                    R.id.audio_attachment_view,
                    R.id.play_audio_button, R.id.replace_audio_button, R.id.remove_audio_button,
                    MSG_PLAY_AUDIO, MSG_REPLACE_AUDIO, MSG_REMOVE_ATTACHMENT);
        } else {
            throw new IllegalArgumentException();
        }
    }

    /**
     * What is the current orientation?
     */
    private boolean inPortraitMode() {
        final Configuration configuration = mContext.getResources().getConfiguration();
        return configuration.orientation == Configuration.ORIENTATION_PORTRAIT;
    }

    private SlideViewInterface createMediaView(
            int stub_view_id, int real_view_id,
            int view_button_id, int replace_button_id, int remove_button_id,
            int view_message, int replace_message, int remove_message) {
        LinearLayout view = (LinearLayout)getStubView(stub_view_id, real_view_id);
        view.setVisibility(View.VISIBLE);

        mViewButton = (Button) view.findViewById(view_button_id);
        mReplaceButton = (Button) view.findViewById(replace_button_id);
        mRemoveButton = (Button) view.findViewById(remove_button_id);



        mViewButton.setOnClickListener(new MessageOnClick(view_message));
        mReplaceButton.setOnClickListener(new MessageOnClick(replace_message));
        mRemoveButton.setOnClickListener(new MessageOnClick(remove_message));

        return (SlideViewInterface) view;
    }

    private SlideViewInterface createSlideshowView(boolean inPortrait) {
        LinearLayout view =(LinearLayout) getStubView(
                R.id.slideshow_attachment_view_stub,
                R.id.slideshow_attachment_view);
        view.setVisibility(View.VISIBLE);

        mEditButton = (Button) view.findViewById(R.id.edit_slideshow_button);

        mSendButton = (Button) view.findViewById(R.id.send_slideshow_button);
        updateSendButton();
        mPlayBtn = (ImageButton) view.findViewById(
                R.id.play_slideshow_button);

        mEditButton.setOnClickListener(new MessageOnClick(MSG_EDIT_SLIDESHOW));
        mSendButton.setOnClickListener(new MessageOnClick(MSG_SEND_SLIDESHOW));
        mPlayBtn.setOnClickListener(new MessageOnClick(MSG_PLAY_SLIDESHOW));

        mRemoveButton = (Button) view.findViewById(R.id.remove_slideshow_button);
        mRemoveButton.setOnClickListener(new MessageOnClick(MSG_REMOVE_ATTACHMENT));

        return (SlideViewInterface) view;
    }
}
