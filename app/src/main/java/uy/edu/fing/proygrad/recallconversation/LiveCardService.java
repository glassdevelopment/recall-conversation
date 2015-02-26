package uy.edu.fing.proygrad.recallconversation;

import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Environment;
import android.os.IBinder;
import android.text.format.Time;
import android.util.Log;
import android.widget.RemoteViews;
import android.widget.Toast;

import com.google.android.glass.timeline.LiveCard;
import com.google.android.glass.timeline.LiveCard.PublishMode;
import com.squareup.otto.Subscribe;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * A {@link Service} that publishes a {@link LiveCard} in the timeline.
 */
public class LiveCardService extends Service {

  private static final String TAG = "LiveCardService";

  public static final int SAMPLE_RATE = 16000;

  private static final String FILE_NAME = "conversation";

  private LiveCard mLiveCard;

  private AudioRecord mRecorder;
  private File mRecording;
  private short[] mBuffer;
  private final String startRecordingLabel = "Start recording";
  private final String stopRecordingLabel = "Stop recording";
  private boolean mIsRecording = false;

  private MediaPlayer mMediaPlayer;

  @Override
  public void onCreate() {
    super.onCreate();

    // Register to the bus
    RecallConversationApplication.bus.register(this);
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (mLiveCard == null) {
      mLiveCard = new LiveCard(this, TAG);

      // Start the record object
      initRecorder();

      updateUI(startRecordingLabel, 0);

      // Display the options menu when the live card is tapped.
      Intent menuIntent = new Intent(this, LiveCardMenuActivity.class);
      mLiveCard.setAction(PendingIntent.getActivity(this, 0, menuIntent, 0));
      mLiveCard.publish(PublishMode.REVEAL);
    } else {
      mLiveCard.navigate();
    }
    return START_STICKY;
  }

  @Override
  public void onDestroy() {
    if (mLiveCard != null && mLiveCard.isPublished()) {
      mLiveCard.unpublish();
      mLiveCard = null;

      // Unregister from the bus
      RecallConversationApplication.bus.unregister(this);
    }
    super.onDestroy();
  }

  @Subscribe
  public void getRecordButtonPressed(RecordAlertEvent a) {
    // Start or stop the recording
    if (!mIsRecording) {
      updateUI(stopRecordingLabel, 0);
      mIsRecording = true;
      mRecorder.startRecording();
      mRecording = getFile("raw");
      startBufferedWrite(mRecording);
    }
    else {
      updateUI(startRecordingLabel, 0);
      mIsRecording = false;
      mRecorder.stop();
      File waveFile = getFile("wav");
      try {
        rawToWave(mRecording, waveFile);
      } catch (IOException e) {
        Toast.makeText(LiveCardService.this, e.getMessage(), Toast.LENGTH_SHORT).show();
      }
      Toast.makeText(LiveCardService.this, "Recorded to " + waveFile.getName(), Toast.LENGTH_SHORT).show();
    }
  }

  @Subscribe
  public void getPlayButtonPressed(PlayAlertEvent event) {
    Log.d(LiveCardService.TAG, "getPlayButtonPressed");
    if (mMediaPlayer == null) {
      mMediaPlayer = new MediaPlayer();
    }

    if (mMediaPlayer.isPlaying()) {
      mMediaPlayer.stop();
      mMediaPlayer.release();
    } else {
      try {
        File audioFile = new File(Environment.getExternalStorageDirectory(), FILE_NAME + "." + "wav");
        mMediaPlayer.setDataSource(audioFile.getPath());
        mMediaPlayer.getDuration();
        mMediaPlayer.prepare();
        mMediaPlayer.start();
      }
      catch (IOException exception) {
        Toast.makeText(LiveCardService.this, exception.getMessage(), Toast.LENGTH_SHORT).show();
      }
      catch (IllegalStateException exception) {

      }
    }
  }

  private void updateUI(String recordingStatus, int progress) {
    RemoteViews remoteViews = new RemoteViews(getPackageName(), R.layout.live_card);
    remoteViews.setTextViewText(R.id.recording_status, recordingStatus);
    remoteViews.setProgressBar(R.id.progress_bar, 100, progress, false);
    mLiveCard.setViews(remoteViews);
  }

  private void initRecorder() {
    int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT);
    mBuffer = new short[bufferSize];
    mRecorder = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT, bufferSize);
  }

  private void startBufferedWrite(final File file) {
    new Thread(new Runnable() {
      @Override
      public void run() {
        DataOutputStream output = null;
        try {
          output = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)));
          while (mIsRecording) {
            double sum = 0;
            int readSize = mRecorder.read(mBuffer, 0, mBuffer.length);
            for (int i = 0; i < readSize; i++) {
              output.writeShort(mBuffer[i]);
              sum += mBuffer[i] * mBuffer[i];
            }
            if (readSize > 0) {
              final double amplitude = sum / readSize;
              //mProgressBar.setProgress((int) Math.sqrt(amplitude));
              updateUI(stopRecordingLabel, (int) Math.sqrt(amplitude));
            }
          }
        } catch (IOException e) {
          Toast.makeText(LiveCardService.this, e.getMessage(), Toast.LENGTH_SHORT).show();
        } finally {
          //mProgressBar.setProgress(0);
          updateUI(stopRecordingLabel, 0);
          if (output != null) {
            try {
              output.flush();
            } catch (IOException e) {
              Toast.makeText(LiveCardService.this, e.getMessage(), Toast.LENGTH_SHORT)
                  .show();
            } finally {
              try {
                output.close();
              } catch (IOException e) {
                Toast.makeText(LiveCardService.this, e.getMessage(), Toast.LENGTH_SHORT)
                    .show();
              }
            }
          }
        }
      }
    }).start();
  }

  private void rawToWave(final File rawFile, final File waveFile) throws IOException {

    byte[] rawData = new byte[(int) rawFile.length()];
    DataInputStream input = null;
    try {
      input = new DataInputStream(new FileInputStream(rawFile));
      input.read(rawData);
    } finally {
      if (input != null) {
        input.close();
      }
    }

    DataOutputStream output = null;
    try {
      output = new DataOutputStream(new FileOutputStream(waveFile));
      // WAVE header
      // see http://ccrma.stanford.edu/courses/422/projects/WaveFormat/
      writeString(output, "RIFF"); // chunk id
      writeInt(output, 36 + rawData.length); // chunk size
      writeString(output, "WAVE"); // format
      writeString(output, "fmt "); // subchunk 1 id
      writeInt(output, 16); // subchunk 1 size
      writeShort(output, (short) 1); // audio format (1 = PCM)
      writeShort(output, (short) 1); // number of channels
      writeInt(output, SAMPLE_RATE); // sample rate
      writeInt(output, SAMPLE_RATE * 2); // byte rate
      writeShort(output, (short) 2); // block align
      writeShort(output, (short) 16); // bits per sample
      writeString(output, "data"); // subchunk 2 id
      writeInt(output, rawData.length); // subchunk 2 size
      // Audio data (conversion big endian -> little endian)
      short[] shorts = new short[rawData.length / 2];
      ByteBuffer.wrap(rawData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts);
      ByteBuffer bytes = ByteBuffer.allocate(shorts.length * 2);
      for (short s : shorts) {
        bytes.putShort(s);
      }
      output.write(bytes.array());
    } finally {
      if (output != null) {
        output.close();
      }
    }
  }

  private File getFile(final String suffix) {
    Time time = new Time();
    time.setToNow();
    return new File(Environment.getExternalStorageDirectory(), FILE_NAME + "." + suffix);
  }

  private void writeInt(final DataOutputStream output, final int value) throws IOException {
    output.write(value >> 0);
    output.write(value >> 8);
    output.write(value >> 16);
    output.write(value >> 24);
  }

  private void writeShort(final DataOutputStream output, final short value) throws IOException {
    output.write(value >> 0);
    output.write(value >> 8);
  }

  private void writeString(final DataOutputStream output, final String value) throws IOException {
    for (int i = 0; i < value.length(); i++) {
      output.write(value.charAt(i));
    }
  }
}
