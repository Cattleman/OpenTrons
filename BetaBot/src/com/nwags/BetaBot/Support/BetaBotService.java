package com.nwags.BetaBot.Support;



import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;

import android.annotation.SuppressLint;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;

abstract public class BetaBotService extends Service {
	public static final String CMD_GET_OK_PROMPT = "{\"gc\":\"?\"}\n";
	public static final String CMD_GET_STATUS_REPORT = "{\"sr\":null}\n";
	public static final String CMD_SET_QR_VERBOSITY = "{\"qv\":2}\n";
	public static final String CMD_ENABLE_JSON_MODE = "{\"ej\":1}\n";
	public static final String CMD_JSON_VERBOSITY = "{\"jv\":5}\n";
	public static final String CMD_DISABLE_LOCAL_ECHO = "{\"ee\":0}\n";
	public static final String CMD_DISABLE_XON_XOFF = "{\"ex\":0}\n";
	public static final String CMD_SET_STATUS_UPDATE_INTERVAL = "{\"si\":250}\n";
	public static final String CMD_GET_MACHINE_SETTINGS = "{\"sys\":null}\n";
	public static final String CMD_SET_UNIT_MM = "{\"gc\":\"g21\"}\n";
	public static final String CMD_SET_UNIT_INCHES = "{\"gc\":\"g20\"}\n";
	public static final String CMD_GET_X_AXIS = "{\"x\":null}\n";
	public static final String CMD_GET_Y_AXIS = "{\"y\":null}\n";
	public static final String CMD_GET_Z_AXIS = "{\"z\":null}\n";
	public static final String CMD_GET_A_AXIS = "{\"a\":null}\n";
	public static final String CMD_GET_B_AXIS = "{\"b\":null}\n";
	public static final String CMD_GET_C_AXIS = "{\"c\":null}\n";
	public static final String CMD_GET_MOTOR_1_SETTINGS = "{\"1\":null}\n";
	public static final String CMD_GET_MOTOR_2_SETTINGS = "{\"2\":null}\n";
	public static final String CMD_GET_MOTOR_3_SETTINGS = "{\"3\":null}\n";
	public static final String CMD_GET_MOTOR_4_SETTINGS = "{\"4\":null}\n";

	// buffer size on TinyG
	public static final int TINYG_BUFFER_SIZE = 254;

	// broadcast messages when we get updated data
	public static final String STATUS = "com.nwags.BetaBot.STATUS";
	public static final String SYSTEM = "com.nwags.BetaBot.SYSTEM";
	public static final String JSON_ERROR = "com.nwags.BetaBot.JSON_ERROR";
	public static final String AXIS_UPDATE = "com.nwags.BetaBot.AXIS_UPDATE";
	public static final String MOTOR_UPDATE = "com.nwags.BetaBot.MOTOR_UPDATE";
	public static final String CONNECTION_STATUS = "com.nwags.BetaBot.CONNECTION_STATUS";
	public static final String RAWS = "com.nwags.BetaBot.RAWS";
	
	
	protected static final String TAG = "TinyG";
	protected Machine machine;
	private final Semaphore serialBufferAvail = new Semaphore(
			TINYG_BUFFER_SIZE, true);
	protected final Semaphore writeLock = new Semaphore(1, true);
	private final BlockingQueue<String> queue = new LinkedBlockingQueue<String>();
	private final IBinder mBinder = new BetaBotBinder();
	private final QueueProcessor procQ = new QueueProcessor();
	private Thread dequeueWorker;
	private boolean paused = false;
	private volatile boolean flushed;
	private BlackBox ioLog;
	private SharedPreferences settings;

	
	
	@Override
	public IBinder onBind(Intent intent) {
		return mBinder;
	}

	@Override
	public void onCreate() {
		settings = PreferenceManager.getDefaultSharedPreferences(this);
		machine = new Machine(getApplicationContext());
	}

	@Override
	public void onDestroy() {
		disconnect();
	}

	public Machine getMachine() {
		return machine;
	}

	public void connect() {
		if (dequeueWorker == null || !dequeueWorker.isAlive()) {
			dequeueWorker = new Thread(procQ);
			dequeueWorker.start();
		}	

		paused = false;
		flushed = false;

		ioLog = new BlackBox();

		//settings = PreferenceManager.getDefaultSharedPreferences(this);
		logging();
}

	abstract protected void write(String cmd);
	abstract protected void write(byte b[]);
	abstract protected void write(byte b[], int length);

	public void logging() {
		if (settings.getBoolean("debug", false))
			if(ioLog!=null)
				ioLog.open();
		else 
			if(ioLog!=null)
				ioLog.close();
	}

	public void disconnect() {
		// Let everyone know we are disconnected
		Bundle b = new Bundle();
		b.putBoolean("connection", false);
		Intent i = new Intent(CONNECTION_STATUS);
		i.putExtras(b);
		sendBroadcast(i, null);
		int inuse = TINYG_BUFFER_SIZE - serialBufferAvail.availablePermits();
		if (inuse > 0)
			serialBufferAvail.release(inuse);
		queue.clear();
		writeLock.release();
		if (dequeueWorker != null)
			dequeueWorker.interrupt();
		dequeueWorker = null;
		ioLog.close();
		Log.d(TAG, "disconnect done");
	}

	public class BetaBotBinder extends Binder {
		public BetaBotService getService() {
			return BetaBotService.this;
		}
	}

	@SuppressLint("DefaultLocale")
	public static String short_jog(String axis, double step) {
		return String.format("g91g0%s%f", axis, step);
	}

	public void send_gcode(String gcode) {
		send_message("{\"gc\": \"" + gcode + "\"}\n"); // In verbose mode
														// 2, only the f is
														// returned for gc
	}

	public void send_command(String cmd) {
		//write(cmd);
		send_message(cmd);
	}
	
	public int getInUse(){
		return TINYG_BUFFER_SIZE - serialBufferAvail.availablePermits();
	}
	
	public void cleanse(){
		try{
			int inuse = TINYG_BUFFER_SIZE - serialBufferAvail.availablePermits();
			if (inuse > 0)
				serialBufferAvail.release(inuse);
			if (!queue.isEmpty()) {
				queue.clear();
				flushed = true;
			}
			paused = false;
			writeLock.release();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	
	// Enqueue a command
	public void send_message(String cmd) {
		try {
			Log.d(TAG, "adding " + cmd);
			queue.put(cmd);
		} catch (InterruptedException e) {
			// This really shouldn't happen
			e.printStackTrace();
		}
	}

	// Pause can be completed by either a resume or a flush.
	public void send_stop() {
		Log.d(TAG, "in send_stop()");
		send_pause();
		send_flush();
	}

	public void send_pause() {
		if (!paused) {
			try {
				writeLock.acquire();
				Log.d(TAG, "sending feedhold");
				write("!");
				ioLog.write("* ", "!\n");
				paused = true;
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}

	public void send_flush() {
			try {
				if (!paused)
					writeLock.acquire();
				Log.d(TAG, "sending queue flush");
				write("%");
				ioLog.write("* ", "%\n");

				Log.d(TAG, "permits: " + serialBufferAvail.availablePermits());
				int inuse = TINYG_BUFFER_SIZE - serialBufferAvail.availablePermits();
				if (inuse > 0)
					serialBufferAvail.release(inuse);
				if (!queue.isEmpty()) {
					queue.clear();
					flushed = true;
				}
				paused = false;
				writeLock.release();
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
	}

	public void send_resume() {
		if (paused) {
			Log.d(TAG, "Sending cycle start");
			write("~");
			ioLog.write("* ", "~\n");
			paused = false;
			writeLock.release();
		}
	}

	public void send_reset() {
		byte[] rst = {0x18};

		Log.d(TAG, "in send_reset()");
		try {
			writeLock.acquire();
			Log.d(TAG, "sending reset");
			ioLog.write("* ", "RESET\n");
			write(rst);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

		int inuse = TINYG_BUFFER_SIZE - serialBufferAvail.availablePermits();
		if (inuse > 0)
			serialBufferAvail.release(inuse);
		if (!queue.isEmpty()) {
			queue.clear();
			flushed = true;
		}
		writeLock.release();
		refresh();
	}

	public Bundle getMotor(int m) {
		return machine.getMotorBundle(m);
	}

	// apply any new values in the bundle to the machine state
	// and sends the necessary change commands to TinyG
	public void putMotor(int m, Bundle b) {
		String cmd = machine.updateMotorBundle(m, b);
		Log.d(TAG, "update motor command: " + cmd);
		send_message(cmd + "\n");
	}

	public void putAxis(int a, Bundle b) {
		String cmd = machine.updateAxisBundle(a, b);
		Log.d(TAG, "update axis command: " + cmd);
		send_message(cmd + "\n");
	}

	public void putSystem(Bundle b) {
		List<String> cmds = machine.updateSystemBundle(b);

		for (String cmd : cmds) {
			Log.d(TAG, "update system command: " + cmd);
			send_message(cmd + "\n");
		}
	}

	public Bundle getAxis(int a) {
		return machine.getAxisBundle(a);
	}

	public Bundle getMachineStatus() {
		return machine.getStatusBundle();
	}

	public int queueSize() {
		return queue.size();
	}

	protected void updateInfo(String line, Bundle b) {
		String json = b.getString("json");
		Intent i;

		ioLog.write("< ", line + "\n");
		if (json != null) {
			if (json.equals("sr")) {
				i = new Intent(STATUS);
				i.putExtras(b);
				sendBroadcast(i, null);
			}
			if (json.equals("error")) {
				ioLog.write("* ", "Parse error on JSON line\n");
				i = new Intent(JSON_ERROR);
				i.putExtras(b);
				sendBroadcast(i, null);
			}
			if (json.equals("x") || json.equals("y") || json.equals("z") ||
					json.equals("a") || json.equals("b") || json.equals("c")) {
				i = new Intent(AXIS_UPDATE);
				i.putExtras(b);
				sendBroadcast(i, null);
			}
			if (json.equals("1") || json.equals("2") || json.equals("3") ||
					json.equals("4")) {
				i = new Intent(MOTOR_UPDATE);
				i.putExtras(b);
				sendBroadcast(i, null);
			}
			if (json.equals("sys")) {
				i = new Intent(SYSTEM);
				i.putExtras(b);
				sendBroadcast(i, null);
			}
		}
		int freed = b.getInt("buffer");
		if (freed > 0)
			serialBufferAvail.release(freed);
	}
	
	protected void sendRaw(String values){
		//Toast.makeText(this, values, Toast.LENGTH_SHORT).show();
		String raws = "rawness";
		Log.d(TAG, raws);
		Bundle bun = new Bundle();
		bun.putString(raws, values);
		Intent i = new Intent(RAWS);
		i.putExtras(bun);
		sendBroadcast(i,null);
	}
	// Asks for the service to send a full update of all state.
	public void refresh() {
		send_message(CMD_DISABLE_LOCAL_ECHO);
		send_message(CMD_JSON_VERBOSITY);
		send_message(CMD_DISABLE_XON_XOFF);
		send_message(CMD_SET_QR_VERBOSITY);
		send_message(CMD_SET_STATUS_UPDATE_INTERVAL);
		send_message(CMD_GET_STATUS_REPORT);

		// Preload all of these for later display
		send_message(CMD_GET_A_AXIS);
		send_message(CMD_GET_B_AXIS);
		send_message(CMD_GET_C_AXIS);
		send_message(CMD_GET_X_AXIS);
		send_message(CMD_GET_Y_AXIS);
		send_message(CMD_GET_Z_AXIS);
		send_message(CMD_GET_MOTOR_1_SETTINGS);
		send_message(CMD_GET_MOTOR_2_SETTINGS);
		send_message(CMD_GET_MOTOR_3_SETTINGS);
		send_message(CMD_GET_MOTOR_4_SETTINGS);
		send_message(CMD_GET_MACHINE_SETTINGS);
	}

	private class QueueProcessor implements Runnable {
		public void run() {
			try {
				while (true) {
					String cmd = queue.take();
					serialBufferAvail.acquire(cmd.length());
					flushed = false;
					writeLock.acquire();
					if (flushed) { // Don't write that last command if we wiped the queue
						Log.d(TAG, "Skipping command line");
						flushed = false;
					} else {
						ioLog.write("> ", cmd);
						write(cmd);
					} writeLock.release();
				}
			} catch (InterruptedException e) {
				Log.d(TAG, "Exiting queue processor");
			}
		}
	}

	
	
}