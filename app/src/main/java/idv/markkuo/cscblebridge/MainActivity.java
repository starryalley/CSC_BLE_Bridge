package idv.markkuo.cscblebridge;

import androidx.appcompat.app.AppCompatActivity;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = MainActivity.class.getSimpleName();

    private TextView tv_speedSensorState, tv_cadenceSensorState,
            tv_speedSensorTimestamp, tv_cadenceSensorTimestamp,
            tv_speed, tv_cadence;
    private Button btn_service;

    private boolean serviceStarted = false;
    private MainActivityReceiver receiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tv_speedSensorState = findViewById(R.id.SpeedSensorStateText);
        tv_cadenceSensorState = findViewById(R.id.CadenceSensorStateText);
        tv_speedSensorTimestamp = findViewById(R.id.SpeedTimestampText);
        tv_cadenceSensorTimestamp = findViewById(R.id.CadenceTimestampText);

        tv_speed = findViewById(R.id.SpeedText);
        tv_cadence = findViewById(R.id.CadenceText);
        btn_service = findViewById(R.id.ServiceButton);

        if (isServiceRunning()) {
            Log.w(TAG, "Service already started");
            serviceStarted = true;
        }

        btn_service.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent i = new Intent(getApplicationContext(), CSCService.class);
                if (!serviceStarted) {
                    Log.d(TAG, "Starting Service");
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        MainActivity.this.startForegroundService(i);
                    else
                        MainActivity.this.startService(i);
                } else {
                    Log.d(TAG, "Stopping Service");
                    MainActivity.this.stopService(i);
                }
                serviceStarted = !serviceStarted;
                updateButtonState();
            }
        });

        receiver = new MainActivityReceiver();
        // register intent from our service
        IntentFilter filter = new IntentFilter();
        filter.addAction("idv.markkuo.cscblebridge.ANTDATA");
        registerReceiver(receiver, filter);
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

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume(): service was " + (serviceStarted ? "started" : "stopped"));
        serviceStarted = isServiceRunning();
        updateButtonState();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        unregisterReceiver(receiver);
    }

    private void resetUi() {
        tv_speedSensorState.setText(getText(R.string.no_data));
        tv_cadenceSensorState.setText(getText(R.string.no_data));
        tv_speedSensorTimestamp.setText(getText(R.string.no_data));
        tv_cadenceSensorTimestamp.setText(getText(R.string.no_data));
        tv_speed.setText(getText(R.string.no_data));
        tv_cadence.setText(getText(R.string.no_data));
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
            final long speedTimestamp = intent.getLongExtra("speed_timestamp", -1);
            final long cadenceTimestamp = intent.getLongExtra("cadence_timestamp", -1);
            final float speed = intent.getFloatExtra("speed", -1.0f);
            final int cadence = intent.getIntExtra("cadence", -1);

            runOnUiThread(new Runnable() {
                @SuppressLint("DefaultLocale")
                @Override
                public void run() {
                    if (statusBSD != null)
                        tv_speedSensorState.setText(statusBSD);
                    if (statusBC != null)
                        tv_cadenceSensorState.setText(statusBC);
                    if (speedTimestamp >= 0)
                        tv_speedSensorTimestamp.setText(String.valueOf(speedTimestamp));
                    if (cadenceTimestamp >= 0)
                        tv_cadenceSensorTimestamp.setText(String.valueOf(cadenceTimestamp));
                    if (speed >= 0.0f)
                        tv_speed.setText(String.format("%.02f", speed));
                    if (cadence >= 0)
                        tv_cadence.setText(String.valueOf(cadence));
                }
            });
        }
    }
}
