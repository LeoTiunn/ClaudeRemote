package com.termux.terminal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * A TerminalSession backed by SSH I/O streams instead of a local subprocess.
 *
 * Data flow:
 *   SSH InputStream → reader thread → mProcessToTerminalIOQueue → emulator
 *   User input → mTerminalToProcessIOQueue → writer thread → SSH OutputStream
 */
public class SshTerminalSession extends TerminalSession {

  private static final String LOG_TAG = "SshTerminalSession";

  private volatile InputStream mSshInput;
  private volatile OutputStream mSshOutput;
  private volatile boolean mRunning = false;
  private volatile ResizeCallback mResizeCallback;

  public interface ResizeCallback {
    void onResize(int cols, int rows);
  }

  public SshTerminalSession(TerminalSessionClient client) {
    super("/bin/sh", "/", new String[]{"/bin/sh"}, new String[0], null, client);
  }

  /**
   * Initialize the terminal emulator without starting I/O.
   * Call on Main thread BEFORE connecting the SSH channel.
   */
  public void initializeEmulator(int columns, int rows) {
    mEmulator = new TerminalEmulator(this, columns, rows, 0, 0, null, mClient);
    mShellPid = 1;
    mRunning = true;
    mClient.setTerminalShellPid(this, mShellPid);
    Logger.logWarn(mClient, LOG_TAG, "Emulator initialized");
  }

  public void setResizeCallback(ResizeCallback callback) {
    mResizeCallback = callback;
  }

  /**
   * Start reader/writer threads with the given SSH streams.
   * Call IMMEDIATELY after channel.connect() on the IO thread,
   * so the reader starts before PipedInputStream can timeout.
   */
  public void startIo(InputStream sshInput, OutputStream sshOutput) {
    mSshInput = sshInput;
    mSshOutput = sshOutput;

    Logger.logWarn(mClient, LOG_TAG, "Starting reader/writer threads");

    new Thread("SshSessionReader") {
      @Override
      public void run() {
        try {
          byte[] buffer = new byte[8192];
          while (mRunning) {
            int read = mSshInput.read(buffer);
            if (read == -1) {
              Logger.logWarn(mClient, LOG_TAG, "Reader EOF");
              break;
            }
            if (read > 0) {
              mProcessToTerminalIOQueue.write(buffer, 0, read);
              mMainThreadHandler.sendEmptyMessage(1);
            }
          }
        } catch (IOException e) {
          Logger.logWarn(mClient, LOG_TAG, "Reader: " + e.getMessage());
        }
        if (mRunning) {
          mRunning = false;
          mMainThreadHandler.sendMessage(mMainThreadHandler.obtainMessage(4, 0));
        }
      }
    }.start();

    new Thread("SshSessionWriter") {
      @Override
      public void run() {
        byte[] buffer = new byte[4096];
        try {
          while (mRunning) {
            int n = mTerminalToProcessIOQueue.read(buffer, true);
            if (n == -1) break;
            mSshOutput.write(buffer, 0, n);
            mSshOutput.flush();
          }
        } catch (IOException e) {
          Logger.logWarn(mClient, LOG_TAG, "Writer: " + e.getMessage());
        }
      }
    }.start();
  }

  @Override
  void cleanupResources(int exitStatus) {
    synchronized (this) {
      mShellPid = -1;
      mShellExitStatus = exitStatus;
    }
    mRunning = false;
    mTerminalToProcessIOQueue.close();
    mProcessToTerminalIOQueue.close();
    try { if (mSshInput != null) mSshInput.close(); } catch (IOException ignored) {}
    try { if (mSshOutput != null) mSshOutput.close(); } catch (IOException ignored) {}
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
    if (mRunning) mTerminalToProcessIOQueue.write(data, offset, count);
  }

  @Override
  public synchronized boolean isRunning() { return mRunning; }

  @Override
  public void finishIfRunning() {
    if (!mRunning) return;
    mRunning = false;
    mTerminalToProcessIOQueue.close();
    mProcessToTerminalIOQueue.close();
    try { if (mSshInput != null) mSshInput.close(); } catch (IOException ignored) {}
    try { if (mSshOutput != null) mSshOutput.close(); } catch (IOException ignored) {}
  }
}
