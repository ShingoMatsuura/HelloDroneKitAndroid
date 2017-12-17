package com.droneschool.hellodrone;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.provider.Settings;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.o3dr.android.client.ControlTower;
import com.o3dr.android.client.Drone;
import com.o3dr.android.client.apis.ControlApi;
import com.o3dr.android.client.apis.VehicleApi;
import com.o3dr.android.client.interfaces.DroneListener;
import com.o3dr.android.client.interfaces.TowerListener;
import com.o3dr.services.android.lib.drone.attribute.AttributeEvent;
import com.o3dr.services.android.lib.drone.attribute.AttributeType;
import com.o3dr.services.android.lib.drone.connection.ConnectionParameter;
import com.o3dr.services.android.lib.drone.property.Altitude;
import com.o3dr.services.android.lib.drone.property.State;
import com.o3dr.services.android.lib.drone.property.Type;
import com.o3dr.services.android.lib.drone.property.VehicleMode;
import com.o3dr.services.android.lib.model.AbstractCommandListener;
import com.o3dr.services.android.lib.model.SimpleCommandListener;

import java.util.Iterator;
import java.util.List;

public class MainActivity extends AppCompatActivity implements DroneListener, TowerListener {

    // ControlTowerインスタンス用変数の追加
    private ControlTower controlTower;

    // ドローンインスタンス用変数
    private Drone drone;

    // ドローンタイプ（コプター、プレーン、ローバー）
    private int droneType = Type.TYPE_UNKNOWN;

    // レジスター用ハンドラー
    private final Handler handler = new Handler();

    // フライトモード用リスト
    private Spinner modeSelector;
    // デバッグ表示テキストフィールド
    private EditText debug;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        final Context context = getApplicationContext();

        // ControlTowerインスタンス生成
        this.controlTower = new ControlTower(context);

        // Droneインスタンス生成
        this.drone = new Drone(context);

        // フライトモード選択時の処理
        this.modeSelector = (Spinner) findViewById(R.id.modeSelect);
        this.modeSelector.setOnItemSelectedListener(new Spinner.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                onFlightModeSelected(view);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Do nothing
            }
        });
        debug = (EditText) findViewById(R.id.debug);
        InputMethodManager imm = (InputMethodManager)
        getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(
                debug.getWindowToken(), 0);
    }

    @Override
    public void  onStart() {
        super.onStart();

        //==========================================================================================

        // 権限設定を行う
        // 権限がない場合、UDP接続ができない

        //request permissions
        int CODE_WRITE_SETTINGS_PERMISSION = 6789;
        boolean permission;

        // Android 6.0以降の場合
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            permission = Settings.System.canWrite(this);
        } else {
            permission = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
        }
        if (permission) {
            // 権限あり
        } else {
            // 権限なし

            // 権限設定表示
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
                intent.setData(Uri.parse("package:" + this.getPackageName()));
                this.startActivityForResult(intent, CODE_WRITE_SETTINGS_PERMISSION);
            } else {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_SETTINGS}, CODE_WRITE_SETTINGS_PERMISSION);
            }
        }

        //==========================================================================================

        // Activity開始時に3DR Servicesと接続する
        this.controlTower.connect(this);

        // フライトモード初期化
        //updateVehicleModesForType(this.droneType);
    }

    @Override
    public void onStop() {
        super.onStop();

        // ドローンとの接続を解除する
        if (this.drone.isConnected()) {
            this.drone.disconnect();
            updateConnectedButton(false);
        }

        // Control Towerへの登録を解除する
        this.controlTower.unregisterDrone(this.drone);

        // Activity修了時に3DR Servicesから切断する
        this.controlTower.disconnect();
    }

    //==============================================================================================
    // 3DR Servicesリスナー
    //==============================================================================================
    @Override
    public void onTowerConnected() {

        // Tower Controlにドローン、ハンドラーを登録する
        this.controlTower.registerDrone(this.drone, this.handler);

        // Droneインスタンスにリスナーを登録する
        this.drone.registerDroneListener(this);

        alertUser("Tower connected");
    }

    @Override
    public void onTowerDisconnected() {
        alertUser("Tower disconnected");
    }

    //==============================================================================================
    // Droneリスナー
    //==============================================================================================

    @Override
    public void onDroneEvent(String event, Bundle extras) {
        debug(event, extras);
        switch (event) {
            // Droneとの接続完了時
            case AttributeEvent.STATE_CONNECTED:
                alertUser("Drone Connected");
                updateConnectedButton(this.drone.isConnected());
                updateArmButton();
                break;

            // Droneとの切断時
            case AttributeEvent.STATE_DISCONNECTED:
                alertUser("Drone Disconnected");
                updateConnectedButton(this.drone.isConnected());
                updateArmButton();
                break;

            // ステート変更時、アーミング実行時
            case AttributeEvent.STATE_UPDATED:
            case AttributeEvent.STATE_ARMING:
                updateArmButton();
                break;

            // 機体種類の更新
            case AttributeEvent.TYPE_UPDATED:
                Type newDroneType = this.drone.getAttribute(AttributeType.TYPE);
                if (newDroneType.getDroneType() != this.droneType) {
                    this.droneType = newDroneType.getDroneType();
                    updateVehicleModesForType(this.droneType);
                }

            // 高度の更新
            case AttributeEvent.ALTITUDE_UPDATED:
                updateAltitude();
                break;
        }
    }

    @Override
    public void onDroneServiceInterrupted(String errorMsg) {
        alertUser(errorMsg);
    }

    //==============================================================================================
    // Helper関数
    //==============================================================================================

    // メッセージ表示
    protected void alertUser(String message) {
        Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show();
    }

    //==============================================================================================
    // UI Updating
    //==============================================================================================

    // ボタンテキスト変更
    protected void updateConnectedButton(Boolean isConnected) {
        Button connectButton = (Button)findViewById(R.id.btnConnect);
        if (isConnected) {
            connectButton.setText("Disonnect");
        } else {
            connectButton.setText("Connect");
        }
    }

    // フライトモードリストの生成
    protected void updateVehicleModesForType(int droneType) {
        List<VehicleMode> vehicleModes = VehicleMode.getVehicleModePerDroneType(droneType);
        ArrayAdapter<VehicleMode> vehicleModeArrayAdapter = new ArrayAdapter<VehicleMode>(this, android.R.layout.simple_spinner_item, vehicleModes);
        vehicleModeArrayAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        this.modeSelector.setAdapter(vehicleModeArrayAdapter);
    }

    // アームボタンテキストの更新
    protected void updateArmButton() {
        State vehicleState = this.drone.getAttribute(AttributeType.STATE);
        Button armButton = (Button) findViewById(R.id.btnArmTakeOff);

        if (!this.drone.isConnected()) {
            armButton.setVisibility(View.INVISIBLE);
        } else {
            armButton.setVisibility(View.VISIBLE);
        }

        if (vehicleState.isFlying()) {
            // Land
            armButton.setText("LAND");
        } else if (vehicleState.isArmed()) {
            // Take off
            armButton.setText("TAKE OFF");
        } else if (vehicleState.isConnected()) {
            // Connected but not Armed
            armButton.setText("ARM");
        }
    }
    // 高度の更新
    protected void updateAltitude() {
        TextView altitudeTextView = (TextView) findViewById(R.id.altitudeValueTextView);
        Altitude droneAltitude = this.drone.getAttribute(AttributeType.ALTITUDE);
        altitudeTextView.setText(String.format("%3.1f", droneAltitude.getAltitude()) + "m");
    }

    // デバッグ表示
    protected void debug(String msg, Bundle extras) {
        String forDebug = "Event: " + msg + "\n";
        if (extras != null) {
            Iterator<String> keys = extras.keySet().iterator();
            while (keys.hasNext()) {
                String key = keys.next();
                forDebug += ("  " + key + "> " + extras.get(key) + "\n");
            }
        }
        debug.append(forDebug+"\n");
    }

    //==============================================================================================
    // UI Event
    //==============================================================================================

    // 接続ボタン押下時の処理
    public void onBtnConnectTap(View view) {
        // 接続済みの場合は切断を実行する
        if(this.drone.isConnected()) {
            this.drone.disconnect();
        } else {
            // UDP
            ConnectionParameter connectionParams = ConnectionParameter.newUdpConnection(null);

            // TCP
            //ConnectionParameter connectionParams = ConnectionParameter.newTcpConnection("10.1.1.10", null);

            // USB
            //ConnectionParameter connectionParams = ConnectionParameter.newUsbConnection(57600, null);

            this.drone.connect(connectionParams);
        }
    }

    // フライトモード変更時の処理
    public void onFlightModeSelected(View view) {
        VehicleMode vehicleMode = (VehicleMode) this.modeSelector.getSelectedItem();
        VehicleApi.getApi(this.drone).setVehicleMode(vehicleMode, new AbstractCommandListener() {
            @Override
            public void onSuccess() {
                alertUser("Vehicle mode change successful.");
            }

            @Override
            public void onError(int executionError) {
                alertUser("Vehicle mode change failed: " + executionError);
            }

            @Override
            public void onTimeout() {
                alertUser("Vehicle mode change timed out.");
            }
        });
    }

    // アームボタン押下
    public void onArmButtonTap(View view) {
        State vehicleState = this.drone.getAttribute(AttributeType.STATE);

        if (vehicleState.isFlying()) {
            // Land
            VehicleApi.getApi(this.drone).setVehicleMode(VehicleMode.COPTER_LAND, new SimpleCommandListener() {
                @Override
                public void onError(int executionError) {
                    alertUser("Unable to land the vehicle.");
                }

                @Override
                public void onTimeout() {
                    alertUser("Unable to land the vehicle.");
                }
            });
        } else if (vehicleState.isArmed()) {
            // Take off
            ControlApi.getApi(this.drone).takeoff(10, new AbstractCommandListener() {

                @Override
                public void onSuccess() {
                    alertUser("Taking off...");
                }

                @Override
                public void onError(int i) {
                    alertUser("Unable to take off.");
                }

                @Override
                public void onTimeout() {
                    alertUser("Unable to take off.");
                }
            });
        } else if (!vehicleState.isConnected()) {
            // Connect
            alertUser("Connect to a drone first");
        } else {
            // Connected but not Armed
            VehicleApi.getApi(this.drone).arm(true, false, new SimpleCommandListener() {
                @Override
                public void onError(int executionError) {
                    alertUser("Unable to arm vehicle.");
                }

                @Override
                public void onTimeout() {
                    alertUser("Arming operation timed out.");
                }
            });
        }
    }

}
