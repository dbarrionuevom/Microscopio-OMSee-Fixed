package cn.com.buildwin.omseefixed;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.os.Bundle;
import android.os.StrictMode;
import android.provider.MediaStore;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class GalleryActivity extends Activity {
    private final List<File> mFiles = new ArrayList<>();
    private GridAdapter mAdapter;
    private TextView mTitleText;
    private TextView mEmptyText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Allow sharing file:// URIs cleanly within local viewer
        StrictMode.VmPolicy.Builder builder = new StrictMode.VmPolicy.Builder();
        StrictMode.setVmPolicy(builder.build());

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0F141C"));

        // Top Bar
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(16), dp(12), dp(16), dp(12));
        topBar.setBackgroundColor(Color.parseColor("#18202C"));

        Button backBtn = createPillButton("← Volver", "#2B3548");
        backBtn.setOnClickListener(v -> finish());
        topBar.addView(backBtn);

        mTitleText = new TextView(this);
        mTitleText.setTextColor(Color.WHITE);
        mTitleText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        mTitleText.setPadding(dp(16), 0, dp(16), 0);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        topBar.addView(mTitleText, titleLp);

        root.addView(topBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout contentFrame = new FrameLayout(this);
        GridView gridView = new GridView(this);
        gridView.setNumColumns(3);
        gridView.setHorizontalSpacing(dp(8));
        gridView.setVerticalSpacing(dp(8));
        gridView.setPadding(dp(12), dp(12), dp(12), dp(12));
        gridView.setClipToPadding(false);

        mAdapter = new GridAdapter();
        gridView.setAdapter(mAdapter);
        gridView.setOnItemClickListener((parent, view, position, id) -> showPreviewDialog(mFiles.get(position)));

        contentFrame.addView(gridView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mEmptyText = new TextView(this);
        mEmptyText.setText("No hay fotos ni vídeos capturados todavía.\nLas capturas se guardan completas (sin franja gris) en Pictures/OMSeeFixed.");
        mEmptyText.setTextColor(Color.parseColor("#8A99AD"));
        mEmptyText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        mEmptyText.setGravity(Gravity.CENTER);
        mEmptyText.setPadding(dp(32), dp(32), dp(32), dp(32));
        contentFrame.addView(mEmptyText, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(contentFrame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        reloadFiles();
    }

    private void reloadFiles() {
        mFiles.clear();
        File dir = MediaCaptureEngine.getMediaDir(this);
        File[] list = dir.listFiles((d, name) -> {
            String lower = name.toLowerCase();
            return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".mp4");
        });
        if (list != null && list.length > 0) {
            Arrays.sort(list, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            mFiles.addAll(Arrays.asList(list));
        }
        mTitleText.setText("Galería OMSee Fixed (" + mFiles.size() + ")");
        mEmptyText.setVisibility(mFiles.isEmpty() ? View.VISIBLE : View.GONE);
        mAdapter.notifyDataSetChanged();
    }

    private void showPreviewDialog(File file) {
        boolean isVideo = file.getName().toLowerCase().endsWith(".mp4");

        LinearLayout dialogLayout = new LinearLayout(this);
        dialogLayout.setOrientation(LinearLayout.VERTICAL);
        dialogLayout.setBackgroundColor(Color.parseColor("#141B24"));
        dialogLayout.setPadding(dp(12), dp(12), dp(12), dp(12));

        TextView nameLabel = new TextView(this);
        nameLabel.setText(file.getName() + " (" + (file.length() / 1024) + " KB)");
        nameLabel.setTextColor(Color.WHITE);
        nameLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        nameLabel.setPadding(dp(4), dp(4), dp(4), dp(10));
        dialogLayout.addView(nameLabel);

        if (isVideo) {
            VideoView vv = new VideoView(this);
            vv.setVideoURI(Uri.fromFile(file));
            vv.setOnPreparedListener(mp -> {
                mp.setLooping(true);
                vv.start();
            });
            LinearLayout.LayoutParams vLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320));
            vLp.gravity = Gravity.CENTER;
            dialogLayout.addView(vv, vLp);
        } else {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            Bitmap bmp = BitmapFactory.decodeFile(file.getAbsolutePath());
            iv.setImageBitmap(bmp);
            LinearLayout.LayoutParams iLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(340));
            dialogLayout.addView(iv, iLp);
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogLayout)
                .setPositiveButton("Cerrar", null)
                .setNeutralButton("Compartir", (d, w) -> {
                    try {
                        Intent share = new Intent(Intent.ACTION_SEND);
                        share.setType(isVideo ? "video/mp4" : "image/jpeg");
                        share.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(file));
                        startActivity(Intent.createChooser(share, "Compartir captura"));
                    } catch (Exception e) {
                        Toast.makeText(this, "Error al compartir: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Eliminar", (d, w) -> {
                    if (file.delete()) {
                        Toast.makeText(this, "Archivo eliminado", Toast.LENGTH_SHORT).show();
                        reloadFiles();
                    }
                })
                .create();
        dialog.show();
    }

    private class GridAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return mFiles.size();
        }

        @Override
        public Object getItem(int position) {
            return mFiles.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            FrameLayout card = new FrameLayout(GalleryActivity.this);
            card.setLayoutParams(new GridView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(130)));
            card.setBackgroundColor(Color.parseColor("#1D2636"));

            ImageView thumbView = new ImageView(GalleryActivity.this);
            thumbView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(thumbView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            File file = mFiles.get(position);
            boolean isVideo = file.getName().toLowerCase().endsWith(".mp4");

            if (isVideo) {
                Bitmap thumb = ThumbnailUtils.createVideoThumbnail(file.getAbsolutePath(), MediaStore.Video.Thumbnails.MINI_KIND);
                thumbView.setImageBitmap(thumb);
            } else {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = 2;
                Bitmap thumb = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
                thumbView.setImageBitmap(thumb);
            }

            TextView badge = new TextView(GalleryActivity.this);
            badge.setText(isVideo ? "▶ VÍDEO MP4" : "FOTO JPG");
            badge.setTextColor(Color.WHITE);
            badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            badge.setPadding(dp(6), dp(2), dp(6), dp(2));
            badge.setBackgroundColor(isVideo ? Color.parseColor("#CCFF3B30") : Color.parseColor("#99000000"));
            FrameLayout.LayoutParams badgeLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            badgeLp.gravity = Gravity.BOTTOM | Gravity.START;
            badgeLp.setMargins(dp(4), dp(4), dp(4), dp(4));
            card.addView(badge, badgeLp);

            return card;
        }
    }

    private Button createPillButton(String text, String hexColor) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(hexColor));
        bg.setCornerRadius(dp(18));
        b.setBackground(bg);
        b.setPadding(dp(14), dp(6), dp(14), dp(6));
        return b;
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }
}
