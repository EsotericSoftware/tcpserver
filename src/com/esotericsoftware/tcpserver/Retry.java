/* Copyright (c) 2017, Esoteric Software
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

/** Manages a thrad which calls {@link #retry()} repeatedly, sleeping when a try has failed. */
public abstract class Retry {
	protected final String category, name;
	protected volatile boolean running;
	boolean daemon;
	final Object runLock = new Object();
	volatile Thread retryThread;
	int delayIndex;
	int[] retryDelays = new int[] {1 * 1000, 3 * 1000, 5 * 1000, 8 * 1000, 13 * 1000};

	public Retry (String category, String name) {
		this.category = category;
		this.name = name;
	}

	/** Starts a thread which calls {@link #initialize()} and then repeatedly calls {@link #retry()}. */
	public void start () {
		synchronized (runLock) {
			stop();
			if (TRACE) trace(category, "Started retry thread: " + name);
			delayIndex = 0;
			running = true;
			retryThread = new Thread(name) {
				public void run () {
					try {
						initialize();
						while (running)
							retry();
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

	/** Interrupts the retry thread and waits for it to terminate. */
	public void stop () {
		synchronized (runLock) {
			if (!running) return;
			running = false;
			Thread retryThread = this.retryThread;
			if (retryThread == Thread.currentThread()) return;
			if (TRACE) trace(category, "Waiting for retry thread to stop: " + name);
			retryThread.interrupt();
			stopped();
			while (this.retryThread == retryThread) {
				try {
					runLock.wait();
				} catch (InterruptedException ex) {
				}
			}
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

	/** Subclasses should call this from {@link #retry()} to indicate success, resets the next failure sleep time. */
	protected void success () {
		delayIndex = 0;
	}

	/** Subclasses should call this from {@link #retry()} to indicate failure, sleeps for some time. */
	protected void failed () {
		if (retryDelays[delayIndex] == 0) throw new RuntimeException("Retry thread failed: " + name);
		try {
			Thread.sleep(retryDelays[delayIndex]);
		} catch (InterruptedException ignored) {
		}
		delayIndex++;
		if (delayIndex == retryDelays.length) delayIndex = 0;
	}

	/** The delays to use for repeated failures. If more failures occur than entries, the last entry is used. If a delay is zero,
	 * the retry thread is stopped by throwing an exception. */
	public void setRetryDelays (int... retryDelays) {
		this.retryDelays = retryDelays;
	}

	public boolean isRunning () {
		return running;
	}

	public void setDaemon (boolean daemon) {
		this.daemon = daemon;
	}
}
