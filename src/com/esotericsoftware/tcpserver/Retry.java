/* Copyright (c) 2017-2021, Esoteric Software
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following
 * conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following
 * disclaimer in the documentation and/or other materials provided with the distribution.
 * 3. Neither the name of the copyright holder nor the names of its contributors may be used to endorse or promote products
 * derived from this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING,
 * BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT
 * SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.esotericsoftware.tcpserver;

import static com.esotericsoftware.minlog.Log.*;

/** Manages a thread which calls {@link #retry()} repeatedly, sleeping when a try has failed. */
public abstract class Retry {
	protected final String category, name;
	protected volatile boolean running;
	boolean daemon;
	final Object runLock = new Object();
	volatile Thread retryThread;
	volatile int retryCount;
	volatile boolean failed;
	int[] retryDelays = new int[] {1 * 1000, 3 * 1000, 5 * 1000, 8 * 1000, 13 * 1000};

	public Retry (String category, String name) {
		this.category = category;
		this.name = name;
	}

	/** Starts a thread which calls {@link #initialize()} and then repeatedly calls {@link #retry()}. If it is already running, it
	 * is stopped and then started. */
	public void start () {
		synchronized (runLock) {
			stop();
			if (TRACE) trace(category, "Started retry thread: " + name);
			retryCount = 0;
			running = true;
			retryThread = new Thread(name) {
				public void run () {
					try {
						initialize();
						while (running) {
							failed = false;

							retry();

							if (failed) {
								int delay = retryDelays[retryCount % retryDelays.length];
								if (delay == 0) throw new RuntimeException("Retry thread failed: " + name);
								retryCount++;
								try {
									sleep(delay);
								} catch (InterruptedException ignored) {
								}
							}
						}
					} catch (Throwable ex) {
						throw new RuntimeException("Retry error: " + name, ex);
					} finally {
						synchronized (runLock) {
							Retry.this.stop();
							if (TRACE) trace(category, "Stopped retry thread: " + name);
							stopped();
							retryThread = null;
							runLock.notifyAll();
						}
					}
				}
			};
			retryThread.setDaemon(daemon);
			retryThread.start();
		}
	}

	/** Interrupts the retry thread and waits for it to terminate. If it is already stopped, nothing is done.
	 * @return true if it was running. */
	public boolean stop () {
		synchronized (runLock) {
			if (!running) return false;
			running = false;
			Thread retryThread = this.retryThread;
			if (retryThread == Thread.currentThread()) return true;
			if (TRACE) trace(category, "Waiting for retry thread to stop: " + name);
			retryThread.interrupt();
			stopped();
			while (this.retryThread == retryThread) {
				try {
					runLock.wait();
				} catch (InterruptedException ex) {
				}
			}
			return true;
		}
	}

	/** Called once after {@link #start()}, on the retry thread. */
	protected void initialize () {
	}

	/** Called repeatedly on the retry thread between {@link #start()} and {@link #stop()}. If a runtime exception is thrown, the
	 * retry thread is stopped. {@link #success()} or {@link #failed()} should be called. */
	abstract protected void retry ();

	/** Called when the retry thread should be stopped. Called on the thread calling {@link #stop()} or on the retry thread if an
	 * exception occurred. */
	protected void stopped () {
	}

	/** Indicates success, resetting the next failure sleep time.
	 * <p>
	 * Can be called any time. Subclasses should call this from {@link #retry()} when the try was successful. */
	public void success () {
		retryCount = 0;
	}

	/** Inidicates failure so there will be a sleep before the next retry.
	 * <p>
	 * Can be called any time. Subclasses should call this from {@link #retry()} when the try was a failure. */
	public void failed () {
		failed = true;
	}

	/** Called on the retry thread to sleep after a failure. */
	protected void sleep (int millis) {
		synchronized (runLock) {
			try {
				if (running) runLock.wait(millis);
			} catch (InterruptedException ignored) {
			}
		}
	}

	/** The delays to use for repeated failures. If more failures occur than entries, the last entry is used. If a delay is zero,
	 * the retry thread is stopped by throwing an exception. */
	public void setRetryDelays (int... retryDelays) {
		this.retryDelays = retryDelays;
	}

	/** Returns the number of retries since a success. */
	public int getRetryCount () {
		return retryCount;
	}

	public boolean isFirstTry () {
		return retryCount == 0;
	}

	public boolean isRunning () {
		return running;
	}

	public void setDaemon (boolean daemon) {
		this.daemon = daemon;
	}

	public String getCategory () {
		return category;
	}

	public String toString () {
		return name;
	}
}
