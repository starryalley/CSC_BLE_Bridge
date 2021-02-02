package idv.markkuo.cscblebridge;

import androidx.appcompat.app.AppCompatActivity;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;


public class MainActivity extends AppCompatActivity  {
    private static final String TAG = MainActivity.class.getSimpleName();

    private TextView tv_speedSensorState, tv_cadenceSensorState, tv_hrSensorState, tv_runSensorState,
            tv_speedSensorTimestamp, tv_cadenceSensorTimestamp, tv_hrSensorTimestamp,
            tv_runSensorTimestamp, tv_speed, tv_cadence, tv_hr, tv_runSpeed, tv_runCadence;
    private Button btn_service;

    private boolean serviceStarted = false;
    private MainActivityReceiver receiver;
    private boolean mBound = false;
    private CSCService mService;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tv_speedSensorState = findViewById(R.id.SpeedSensorStateText);
        tv_cadenceSensorState = findViewById(R.id.CadenceSensorStateText);
        tv_hrSensorState = findViewById(R.id.HRSensorStateText);
        tv_runSensorState = findViewById(R.id.RunSensorStateText);

        tv_speedSensorTimestamp = findViewById(R.id.SpeedTimestampText);
        tv_cadenceSensorTimestamp = findViewById(R.id.CadenceTimestampText);
        tv_hrSensorTimestamp = findViewById(R.id.HRTimestampText);
        tv_runSensorTimestamp = findViewById(R.id.RunTimestampText);

        tv_speed = findViewById(R.id.SpeedText);
        tv_cadence = findViewById(R.id.CadenceText);
        tv_hr = findViewById(R.id.HRText);
        tv_runSpeed = findViewById(R.id.RunSpeedText);
        tv_runCadence = findViewById(R.id.RunCadenceText);
        btn_service = findViewById(R.id.ServiceButton);

        if (isServiceRunning()) {
            Log.w(TAG, "Service already started");
            serviceStarted = true;
        }

        btn_service.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent mServiceIntent = new Intent(getApplicationContext(), CSCService.class);
                if (!serviceStarted) {
                    Log.d(TAG, "Starting Service");
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        MainActivity.this.startForegroundService(mServiceIntent);
                    else
                        MainActivity.this.startService(mServiceIntent);

                    // Bind to the service so we can interact with it
                    if (!bindService(mServiceIntent, connection, Context.BIND_AUTO_CREATE)) {
                        Log.d(TAG, "Failed to bind to service");
                    } else {
                        mBound = true;
                    }
                }
                else {
                    Log.d(TAG, "Stopping Service");
                    unbindService();
                    MainActivity.this.stopService(mServiceIntent);
                }

                serviceStarted = !serviceStarted;
                updateButtonState();
            }
        });

        // Bind to the Sensor title and install a long click listener to restart searching for Speed sensor
        findViewById(R.id.SpeedSensorText).setOnLongClickListener(createLongClickListener(() -> mService.startSpeedSensorSearch()));

        // Bind to the Sensor title and install a long click listener to restart searching for Cadence sensor
        findViewById(R.id.CadenceSensorText).setOnLongClickListener(createLongClickListener(() -> mService.startCadenceSensorSearch()));

        // Bind to the Sensor title and install a long click listener to restart searching for HR sensor
        findViewById(R.id.HRSensorText).setOnLongClickListener(createLongClickListener(() -> mService.startHRSensorSearch()));

        receiver = new MainActivityReceiver();
        // register intent from our service
        IntentFilter filter = new IntentFilter();
        filter.addAction("idv.markkuo.cscblebridge.ANTDATA");
        registerReceiver(receiver, filter);
    }

    // Allows for lambdas on the long click handler to reduce code duplication.
    // TODO: Investigate to see if there is an existing interface we can use
    interface DoAction {
        void action();
    }

    // Long click handler for restarting scan for Ant+ devices
    private View.OnLongClickListener createLongClickListener(final DoAction action) {
        return new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (!serviceStarted || !mBound) {
                    return false;
                }

                action.action();
                return true;
            }
        };
    }

    private void updateButtonState() {
        // update the title
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                resetUi();
                if (serviceStarted)
                    btn_service.setText(getText(R.string.stop_service));
                else
                    btn_service.setText(getText(R.string.start_service));
            }
        });
    }

    /** Defines callbacks for service binding, passed to bindService() */
    private ServiceConnection connection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName className,  IBinder service) {
            CSCService.LocalBinder binder = (CSCService.LocalBinder) service;
            mService = binder.getService();
            mBound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            mBound = false;
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume(): service was " + (serviceStarted ? "started" : "stopped"));
        serviceStarted = isServiceRunning();
        updateButtonState();
    }

    // Unbind from the service
    void unbindService() {
        if (mBound) {
            unbindService(connection);
            mBound = false;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        unregisterReceiver(receiver);
        unbindService();
    }

    private void resetUi() {
        tv_speedSensorState.setText(getText(R.string.no_data));
        tv_cadenceSensorState.setText(getText(R.string.no_data));
        tv_hrSensorState.setText(getText(R.string.no_data));
        tv_runSensorState.setText(getText(R.string.no_data));
        tv_speedSensorTimestamp.setText(getText(R.string.no_data));
        tv_cadenceSensorTimestamp.setText(getText(R.string.no_data));
        tv_hrSensorTimestamp.setText(getText(R.string.no_data));
        tv_runSensorTimestamp.setText(getText(R.string.no_data));
        tv_speed.setText(getText(R.string.no_data));
        tv_cadence.setText(getText(R.string.no_data));
        tv_hr.setText(getText(R.string.no_data));
    }

    private boolean isServiceRunning() {
        ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        assert manager != null;
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (CSCService.class.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    // this BroadcastReceiver is used to update UI only
    private class MainActivityReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String statusBSD = intent.getStringExtra("bsd_service_status");
            final String statusBC = intent.getStringExtra("bc_service_status");
            final String statusHR = intent.getStringExtra("hr_service_status");
            final String statusRsc = intent.getStringExtra("ss_service_status");
            final long speedTimestamp = intent.getLongExtra("speed_timestamp", -1);
            final long cadenceTimestamp = intent.getLongExtra("cadence_timestamp", -1);
            final long hrTimestamp = intent.getLongExtra("hr_timestamp", -1);
            final long runTimestamp = intent.getLongExtra("ss_stride_count_timestamp", -1);
            final float speed = intent.getFloatExtra("speed", -1.0f);
            final int cadence = intent.getIntExtra("cadence", -1);
            final int hr = intent.getIntExtra("hr", -1);
            final float runSpeed = intent.getFloatExtra("ss_speed", -1);
            final long runStrideCount = intent.getLongExtra("ss_stride_count", -1);

            runOnUiThread(new Runnable() {
                @SuppressLint("DefaultLocale")
                @Override
                public void run() {
                    if (statusBSD != null)
                        tv_speedSensorState.setText(statusBSD);
                    if (statusBC != null)
                        tv_cadenceSensorState.setText(statusBC);
                    if (statusHR != null)
                        tv_hrSensorState.setText(statusHR);
                    if (statusRsc != null)
                        tv_runSensorState.setText(statusRsc);
                    if (speedTimestamp >= 0)
                        tv_speedSensorTimestamp.setText(String.valueOf(speedTimestamp));
                    if (cadenceTimestamp >= 0)
                        tv_cadenceSensorTimestamp.setText(String.valueOf(cadenceTimestamp));
                    if (hrTimestamp >= 0)
                        tv_hrSensorTimestamp.setText(String.valueOf(hrTimestamp));
                    if (runTimestamp >= 0)
                        tv_runSensorTimestamp.setText(String.valueOf(runTimestamp));
                    if (speed >= 0.0f)
                        tv_speed.setText(String.format("%.02f", speed));
                    if (cadence >= 0)
                        tv_cadence.setText(String.valueOf(cadence));
                    if (hr >= 0)
                        tv_hr.setText(String.valueOf(hr));
                    if (runSpeed >= 0)
                        tv_runSpeed.setText(String.format("%.02f", runSpeed * 3.6));
                    if (runStrideCount >= 0) {
                        tv_runCadence.setText(String.valueOf(runStrideCount * 2));
                    }
                }
            });
        }
    }
}
