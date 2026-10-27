package app.piggy.desk;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.ContentInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.view.inputmethod.InputContentInfo;
import android.widget.EditText;

import java.util.ArrayList;
import java.util.List;

/**
 * The chat composers' text field (the bubble's and the chat screen's): it also takes images, from the keyboard (its
 * clipboard, stickers, GIFs) and from Paste, and hands them over to be attached instead of pasting text (issue #6).
 */
final class RichEditText extends EditText {
    interface Images {
        /** Images to attach: call done once they're copied, which lets go of the keyboard's access to them. */
        void onImages(List<Uri> uris, Runnable done);
    }

    private static final String[] TYPES = {"image/*"};
    private Images images;
    /** What came from the keyboard while its images are copied: the access it granted lasts while it's kept. */
    private final List<Object> holding = new ArrayList<>();

    RichEditText(Context c) {
        super(c);
    }

    void onImages(Images l) {
        images = l;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return; // Android 11: see onCreateInputConnection
        // one listener for the keyboard, Paste and drag and drop; whatever isn't an image is inserted as usual
        setOnReceiveContentListener(TYPES, (view, payload) -> {
            ClipData clip = payload.getClip();
            List<Uri> uris = new ArrayList<>();
            ClipData rest = null;
            for (int i = 0; i < clip.getItemCount(); i++) {
                ClipData.Item item = clip.getItemAt(i);
                if (item.getUri() != null && isImage(getContext(), item.getUri(), clip.getDescription())) {
                    uris.add(item.getUri());
                } else if (rest == null) {
                    rest = new ClipData(clip.getDescription(), item);
                } else {
                    rest.addItem(item);
                }
            }
            if (!uris.isEmpty()) {
                holding.add(payload);
                images.onImages(uris, () -> holding.remove(payload));
            }
            return rest == null ? null : new ContentInfo.Builder(payload).setClip(rest).build();
        });
    }

    /** Android 11 has no receive-content listener: the keyboard's images come through commitContent instead. */
    @Override
    public InputConnection onCreateInputConnection(EditorInfo out) {
        InputConnection ic = super.onCreateInputConnection(out);
        if (ic == null || images == null || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return ic;
        out.contentMimeTypes = TYPES;
        return new InputConnectionWrapper(ic, false) {
            @Override
            public boolean commitContent(InputContentInfo info, int flags, Bundle opts) {
                if (!info.getDescription().hasMimeType("image/*")) return super.commitContent(info, flags, opts);
                if ((flags & INPUT_CONTENT_GRANT_READ_URI_PERMISSION) != 0) {
                    try {
                        info.requestPermission();
                    } catch (Exception e) {
                        return false;
                    }
                }
                List<Uri> uris = new ArrayList<>();
                uris.add(info.getContentUri());
                images.onImages(uris, info::releasePermission);
                return true;
            }
        };
    }

    /** Is the clipboard or keyboard item at this address an image? (Its own type first, else the clip's.) */
    static boolean isImage(Context c, Uri uri, ClipDescription d) {
        String type = null;
        try {
            type = c.getContentResolver().getType(uri);
        } catch (Exception ignored) {
        }
        return type != null ? type.startsWith("image/") : d.hasMimeType("image/*");
    }
}
