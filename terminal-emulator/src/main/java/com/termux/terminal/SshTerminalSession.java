package com.termux.terminal;

import android.os.Handler;
import android.os.Message;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * A TerminalSession that uses external I/O streams (e.g., from JSch SSH channel)
 * instead of a local subprocess + PTY.
 *
 * Data flow:
 *   SSH InputStream → mProcessToTerminalIOQueue → TerminalEmulator → display
 *   User input → mTerminalToProcessIOQueue → SSH OutputStream → remote server
 */
public class SshTerminalSession extends TerminalSession {

  private volatile InputStream mSshInput;
  private volatile OutputStream mSshOutput;
  private volatile boolean mRunning = false;
  private volatile Thread mReaderThread;
  private volatile Thread mWriterThread;
  private ResizeCallback mResizeCallback;

  public interface ResizeCallback {
    void onResize(int cols, int rows);
  }

  private static final String LOG_TAG = "SshTerminalSession";

  public SshTerminalSession(TerminalSessionClient client) {
    super("/bin/sh", "/", new String[]{"/bin/sh"}, new String[0], null, client);
  }

  /**
   * Initialize the emulator and connect to the given SSH I/O streams.
   * Call this AFTER the SSH channel is opened and has valid streams.
   */
  public void initializeWithStreams(
      int columns, int rows, int cellWidthPixels, int cellHeightPixels,
      InputStream sshInput, OutputStream sshOutput, ResizeCallback resizeCallback) {

    mSshInput = sshInput;
    mSshOutput = sshOutput;
    mResizeCallback = resizeCallback;
    mRunning = true;

    // Create emulator without subprocess
    mEmulator = new TerminalEmulator(this, columns, rows, cellWidthPixels, cellHeightPixels, null, mClient);
    mShellPid = 1; // Fake PID to indicate "running"
    mClient.setTerminalShellPid(this, mShellPid);

    // Reader: SSH input → emulator
    mReaderThread = new Thread("SshSessionReader") {
      @Override
      public void run() {
        Logger.logWarn(mClient, LOG_TAG, "Reader thread started");
        try {
          byte[] buffer = new byte[8192];
          while (mRunning) {
            int read = mSshInput.read(buffer);
            if (read == -1) {
              Logger.logWarn(mClient, LOG_TAG, "Reader got EOF (-1)");
              break;
            }
            if (read > 0) {
              mProcessToTerminalIOQueue.write(buffer, 0, read);
              mMainThreadHandler.sendEmptyMessage(1); // MSG_NEW_INPUT
            }
          }
        } catch (IOException e) {
          Logger.logWarn(mClient, LOG_TAG, "Reader IOException: " + e.getMessage());
        } catch (Exception e) {
          Logger.logWarn(mClient, LOG_TAG, "Reader Exception: " + e.getClass().getName() + ": " + e.getMessage());
        }
        Logger.logWarn(mClient, LOG_TAG, "Reader thread exiting, mRunning=" + mRunning);
        if (mRunning) {
          mRunning = false;
          mMainThreadHandler.sendMessage(mMainThreadHandler.obtainMessage(4, 0)); // MSG_PROCESS_EXITED
        }
      }
    };
    mReaderThread.start();

    // Writer: emulator output → SSH
    mWriterThread = new Thread("SshSessionWriter") {
      @Override
      public void run() {
        Logger.logWarn(mClient, LOG_TAG, "Writer thread started");
        byte[] buffer = new byte[4096];
        try {
          while (mRunning) {
            int bytesToWrite = mTerminalToProcessIOQueue.read(buffer, true);
            if (bytesToWrite == -1) {
              Logger.logWarn(mClient, LOG_TAG, "Writer got -1 from queue");
              break;
            }
            mSshOutput.write(buffer, 0, bytesToWrite);
            mSshOutput.flush();
          }
        } catch (IOException e) {
          Logger.logWarn(mClient, LOG_TAG, "Writer IOException: " + e.getMessage());
        }
        Logger.logWarn(mClient, LOG_TAG, "Writer thread exiting");
      }
    };
    mWriterThread.start();
  }

  @Override
  void cleanupResources(int exitStatus) {
    // Override parent to avoid JNI.close(mTerminalFileDescriptor) which would close fd 0 (stdin)
    Logger.logWarn(mClient, LOG_TAG, "cleanupResources called with exitStatus=" + exitStatus);
    synchronized (this) {
      mShellPid = -1;
      mShellExitStatus = exitStatus;
    }
    mTerminalToProcessIOQueue.close();
    mProcessToTerminalIOQueue.close();
    try { if (mSshInput != null) mSshInput.close(); } catch (IOException ignored) {}
    try { if (mSshOutput != null) mSshOutput.close(); } catch (IOException ignored) {}
  }

  @Override
  public void updateSize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
    if (mEmulator == null) {
      // First call — but we initialize manually via initializeWithStreams, so do nothing
      return;
    }
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
    try { if (mSshInput != null) mSshInput.close(); } catch (IOException ignored) {}
    try { if (mSshOutput != null) mSshOutput.close(); } catch (IOException ignored) {}
  }
}
