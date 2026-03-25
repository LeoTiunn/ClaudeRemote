package com.termux.terminal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * A TerminalSession backed by SSH I/O streams instead of a local subprocess.
 *
 * Instead of using JSch's getInputStream()/getOutputStream() (which use PipedInputStream
 * internally and have timeout issues), this class provides custom streams that bridge
 * directly to the ByteQueue. The caller sets these on the JSch channel via
 * channel.setOutputStream() / channel.setInputStream() BEFORE channel.connect().
 *
 * Data flow:
 *   JSch internal thread → sshDataReceiver (OutputStream) → mProcessToTerminalIOQueue → emulator
 *   User input → mTerminalToProcessIOQueue → userInputProvider (InputStream) → JSch internal thread
 */
public class SshTerminalSession extends TerminalSession {

  private static final String LOG_TAG = "SshTerminalSession";

  private volatile boolean mRunning = false;
  private ResizeCallback mResizeCallback;

  /** OutputStream that JSch writes SSH channel data to — goes directly into the terminal queue. */
  private final OutputStream mSshDataReceiver;

  /** InputStream that JSch reads user input from — pulls directly from the terminal queue. */
  private final InputStream mUserInputProvider;

  public interface ResizeCallback {
    void onResize(int cols, int rows);
  }

  public SshTerminalSession(TerminalSessionClient client) {
    super("/bin/sh", "/", new String[]{"/bin/sh"}, new String[0], null, client);

    // SSH data → terminal emulator queue (JSch writes here directly)
    mSshDataReceiver = new OutputStream() {
      @Override
      public void write(int b) throws IOException {
        write(new byte[]{(byte) b}, 0, 1);
      }

      @Override
      public void write(byte[] b, int off, int len) throws IOException {
        if (!mRunning) throw new IOException("Session closed");
        if (len > 0) {
          mProcessToTerminalIOQueue.write(b, off, len);
          mMainThreadHandler.sendEmptyMessage(1); // MSG_NEW_INPUT
        }
      }
    };

    // Terminal queue → SSH channel (JSch reads from here directly)
    mUserInputProvider = new InputStream() {
      @Override
      public int read() throws IOException {
        byte[] buf = new byte[1];
        int n = read(buf, 0, 1);
        return n == -1 ? -1 : buf[0] & 0xFF;
      }

      @Override
      public int read(byte[] b, int off, int len) throws IOException {
        if (len == 0) return 0;
        byte[] temp = new byte[len];
        int n = mTerminalToProcessIOQueue.read(temp, true);
        if (n <= 0) return -1;
        System.arraycopy(temp, 0, b, off, n);
        return n;
      }
    };
  }

  /** Get the OutputStream to set on the JSch channel via channel.setOutputStream(). */
  public OutputStream getSshDataReceiver() {
    return mSshDataReceiver;
  }

  /** Set the resize callback (can be set after construction). */
  public void setResizeCallback(ResizeCallback callback) {
    mResizeCallback = callback;
  }

  /** Get the InputStream to set on the JSch channel via channel.setInputStream(). */
  public InputStream getUserInputProvider() {
    return mUserInputProvider;
  }

  /**
   * Initialize the terminal emulator. Call BEFORE channel.connect().
   * No reader/writer threads needed — JSch handles I/O via the custom streams.
   */
  public void initializeEmulatorForSsh(
      int columns, int rows, int cellWidthPixels, int cellHeightPixels,
      ResizeCallback resizeCallback) {

    mResizeCallback = resizeCallback;
    mRunning = true;

    mEmulator = new TerminalEmulator(this, columns, rows, cellWidthPixels, cellHeightPixels, null, mClient);
    mShellPid = 1;
    mClient.setTerminalShellPid(this, mShellPid);

    Logger.logWarn(mClient, LOG_TAG, "Emulator initialized, ready for channel.connect()");
  }

  /**
   * Called when the JSch channel has been closed/disconnected.
   * Signals the terminal that the "process" has exited.
   */
  public void notifyChannelClosed() {
    if (!mRunning) return;
    Logger.logWarn(mClient, LOG_TAG, "Channel closed notification received");
    mRunning = false;
    mMainThreadHandler.sendMessage(mMainThreadHandler.obtainMessage(4, 0)); // MSG_PROCESS_EXITED
  }

  @Override
  void cleanupResources(int exitStatus) {
    Logger.logWarn(mClient, LOG_TAG, "cleanupResources called with exitStatus=" + exitStatus);
    synchronized (this) {
      mShellPid = -1;
      mShellExitStatus = exitStatus;
    }
    mRunning = false;
    mTerminalToProcessIOQueue.close();
    mProcessToTerminalIOQueue.close();
    // No JNI.close() — we have no file descriptor
  }

  @Override
  public void updateSize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
    if (mEmulator == null) return;
    mEmulator.resize(columns, rows, cellWidthPixels, cellHeightPixels);
    if (mResizeCallback != null) {
      mResizeCallback.onResize(columns, rows);
    }
  }

  @Override
  public void write(byte[] data, int offset, int count) {
    if (mRunning) {
      mTerminalToProcessIOQueue.write(data, offset, count);
    }
  }

  @Override
  public synchronized boolean isRunning() {
    return mRunning;
  }

  @Override
  public void finishIfRunning() {
    if (!mRunning) return;
    mRunning = false;
    mTerminalToProcessIOQueue.close();
    mProcessToTerminalIOQueue.close();
  }
}
