package info.nightscout.androidaps.plugins.PumpDanaRv2.services;

import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.SystemClock;

import com.squareup.otto.Subscribe;

import java.io.IOException;
import java.util.Date;

import info.nightscout.androidaps.Constants;
import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.data.Profile;
import info.nightscout.androidaps.data.PumpEnactResult;
import info.nightscout.androidaps.events.EventAppExit;
import info.nightscout.androidaps.events.EventInitializationChanged;
import info.nightscout.androidaps.events.EventPreferenceChange;
import info.nightscout.androidaps.events.EventProfileSwitchChange;
import info.nightscout.androidaps.events.EventPumpStatusChanged;
import info.nightscout.androidaps.interfaces.PumpInterface;
import info.nightscout.androidaps.logging.L;
import info.nightscout.androidaps.plugins.ConfigBuilder.ConfigBuilderPlugin;
import info.nightscout.androidaps.plugins.ConfigBuilder.ProfileFunctions;
import info.nightscout.androidaps.plugins.Overview.Dialogs.BolusProgressDialog;
import info.nightscout.androidaps.plugins.Overview.Dialogs.ErrorHelperActivity;
import info.nightscout.androidaps.plugins.Overview.events.EventNewNotification;
import info.nightscout.androidaps.plugins.Overview.events.EventOverviewBolusProgress;
import info.nightscout.androidaps.plugins.Overview.notifications.Notification;
import info.nightscout.androidaps.plugins.PumpDanaR.DanaRPump;
import info.nightscout.androidaps.plugins.PumpDanaR.comm.MessageBase;
import info.nightscout.androidaps.plugins.PumpDanaR.comm.MsgBolusProgress;
import info.nightscout.androidaps.plugins.PumpDanaR.comm.MsgBolusStart;
import info.nightscout.androidaps.plugins.PumpDanaR.comm.MsgBolusStartWithSpeed;
import info.nightscout.androidaps.plugins.PumpDanaR.comm.MsgBolusStop;
import info.nightscout.androidaps.plugins.PumpDanaR.comm.MsgSetActivateBasalProfile;
import info.nightscout.androidaps.plugins.PumpDanaR.comm.MsgSetBasalProfile;
import info.nightscout.androidaps.plugins.PumpDanaR.comm.MsgSetCarbsEntry;
import info.nightscout.androidaps.plugins.PumpDanaR.comm.MsgSetExtendedBolusStart;
import info.nightscout.androidaps.plugins.PumpDanaR.comm.MsgSetExtendedBolusStop;
import info.nightscout.androidaps.plugins.PumpDanaR.comm.MsgSetTempBasalStart;
import info.nightscout.androidaps.plugins.PumpDanaR.comm.MsgSetTempBasalStop;
import info.nightscout.androidaps.plugins.PumpDanaR.comm.MsgSetTime;
import info.nightscout.androidaps.plugins.PumpDanaR.comm.MsgSetUserOptions;
import info.nightscout.androidaps.plugins.PumpDanaR.comm.MsgSettingActiveProfile;
import info.nightscout.androidaps.plugins.PumpDanaR.comm.MsgSettingBasal;
import info.nightscout.androidaps.plugins.PumpDanaR.comm.MsgSettingGlucose;
import info.nightscout.androidaps.plugins.PumpDanaR.comm.MsgSettingMaxValues;
import info.nightscout.androidaps.plugins.PumpDanaR.comm.MsgSettingMeal;
import info.nightscout.androidaps.plugins.PumpDanaR.comm.MsgSettingProfileRatios;
import info.nightscout.androidaps.plugins.PumpDanaR.comm.MsgSettingProfileRatiosAll;
import info.nightscout.androidaps.plugins.PumpDanaR.comm.MsgSettingPumpTime;
import info.nightscout.androidaps.plugins.PumpDanaR.comm.MsgSettingShippingInfo;
import info.nightscout.androidaps.plugins.PumpDanaR.comm.MsgSettingUserOptions;
import info.nightscout.androidaps.plugins.PumpDanaR.comm.MsgStatus;
import info.nightscout.androidaps.plugins.PumpDanaR.comm.MsgStatusBasic;
import info.nightscout.androidaps.plugins.PumpDanaR.events.EventDanaRNewStatus;
import info.nightscout.androidaps.plugins.PumpDanaR.services.AbstractDanaRExecutionService;
import info.nightscout.androidaps.plugins.PumpDanaRv2.DanaRv2Plugin;
import info.nightscout.androidaps.plugins.PumpDanaRv2.SerialIOThread;
import info.nightscout.androidaps.plugins.PumpDanaRv2.comm.MsgCheckValue_v2;
import info.nightscout.androidaps.plugins.PumpDanaRv2.comm.MsgHistoryEvents_v2;
import info.nightscout.androidaps.plugins.PumpDanaRv2.comm.MsgSetAPSTempBasalStart_v2;
import info.nightscout.androidaps.plugins.PumpDanaRv2.comm.MsgSetHistoryEntry_v2;
import info.nightscout.androidaps.plugins.PumpDanaRv2.comm.MsgStatusBolusExtended_v2;
import info.nightscout.androidaps.plugins.PumpDanaRv2.comm.MsgStatusTempBasal_v2;
import info.nightscout.androidaps.plugins.Treatments.Treatment;
import info.nightscout.androidaps.queue.Callback;
import info.nightscout.androidaps.queue.commands.Command;
import info.nightscout.utils.DateUtil;
import info.nightscout.androidaps.plugins.NSClientInternal.NSUpload;
import info.nightscout.utils.SP;
import info.nightscout.utils.T;

public class DanaRv2ExecutionService extends AbstractDanaRExecutionService {

    private long lastHistoryFetched = 0;

    public DanaRv2ExecutionService() {
        mBinder = new LocalBinder();

        registerBus();
        MainApp.instance().getApplicationContext().registerReceiver(receiver, new IntentFilter(BluetoothDevice.ACTION_ACL_DISCONNECTED));
    }

    public class LocalBinder extends Binder {
        public DanaRv2ExecutionService getServiceInstance() {
            return DanaRv2ExecutionService.this;
        }
    }

    private void registerBus() {
        try {
            MainApp.bus().unregister(this);
        } catch (RuntimeException x) {
            // Ignore
        }
        MainApp.bus().register(this);
    }

    @Subscribe
    public void onStatusEvent(EventAppExit event) {
        if (L.isEnabled(L.PUMP))
            log.debug("EventAppExit received");

        if (mSerialIOThread != null)
            mSerialIOThread.disconnect("Application exit");

        MainApp.instance().getApplicationContext().unregisterReceiver(receiver);

        stopSelf();
    }

    @Subscribe
    public void onStatusEvent(final EventPreferenceChange pch) {
        if (mSerialIOThread != null)
            mSerialIOThread.disconnect("EventPreferenceChange");
    }

    public void connect() {
        if (mConnectionInProgress)
            return;

        new Thread(() -> {
            mHandshakeInProgress = false;
            mConnectionInProgress = true;
            getBTSocketForSelectedPump();
            if (mRfcommSocket == null || mBTDevice == null) {
                mConnectionInProgress = false;
                return; // Device not found
            }

            try {
                mRfcommSocket.connect();
            } catch (IOException e) {
                //log.error("Unhandled exception", e);
                if (e.getMessage().contains("socket closed")) {
                    log.error("Unhandled exception", e);
                }
            }

            if (isConnected()) {
                if (mSerialIOThread != null) {
                    mSerialIOThread.disconnect("Recreate SerialIOThread");
                }
                mSerialIOThread = new SerialIOThread(mRfcommSocket);
                mHandshakeInProgress = true;
                MainApp.bus().post(new EventPumpStatusChanged(EventPumpStatusChanged.HANDSHAKING, 0));
            }

            mConnectionInProgress = false;
        }).start();
    }

    public void getPumpStatus() {
        try {
            MainApp.bus().post(new EventPumpStatusChanged(MainApp.gs(R.string.gettingpumpstatus)));
            MsgStatus statusMsg = new MsgStatus();
            MsgStatusBasic statusBasicMsg = new MsgStatusBasic();
            MsgStatusTempBasal_v2 tempStatusMsg = new MsgStatusTempBasal_v2();
            MsgStatusBolusExtended_v2 exStatusMsg = new MsgStatusBolusExtended_v2();
            MsgCheckValue_v2 checkValue = new MsgCheckValue_v2();

            if (mDanaRPump.isNewPump) {
                mSerialIOThread.sendMessage(checkValue);
                if (!checkValue.received) {
                    return;
                }
            }

            MainApp.bus().post(new EventPumpStatusChanged(MainApp.gs(R.string.gettingbolusstatus)));
            mSerialIOThread.sendMessage(statusMsg);
            mSerialIOThread.sendMessage(statusBasicMsg);
            MainApp.bus().post(new EventPumpStatusChanged(MainApp.gs(R.string.gettingtempbasalstatus)));
            mSerialIOThread.sendMessage(tempStatusMsg);
            MainApp.bus().post(new EventPumpStatusChanged(MainApp.gs(R.string.gettingextendedbolusstatus)));
            mSerialIOThread.sendMessage(exStatusMsg);

            mDanaRPump.lastConnection = System.currentTimeMillis();

            Profile profile = ProfileFunctions.getInstance().getProfile();
            PumpInterface pump = ConfigBuilderPlugin.getPlugin().getActivePump();
            if (profile != null && Math.abs(mDanaRPump.currentBasal - profile.getBasal()) >= pump.getPumpDescription().basalStep) {
                MainApp.bus().post(new EventPumpStatusChanged(MainApp.gs(R.string.gettingpumpsettings)));
                mSerialIOThread.sendMessage(new MsgSettingBasal());
                if (!pump.isThisProfileSet(profile) && !ConfigBuilderPlugin.getPlugin().getCommandQueue().isRunning(Command.CommandType.BASALPROFILE)) {
                    MainApp.bus().post(new EventProfileSwitchChange());
                }
            }

            MainApp.bus().post(new EventPumpStatusChanged(MainApp.gs(R.string.gettingpumptime)));
            mSerialIOThread.sendMessage(new MsgSettingPumpTime());
            long timeDiff = (mDanaRPump.pumpTime - System.currentTimeMillis()) / 1000L;
            if (L.isEnabled(L.PUMP))
                log.debug("Pump time difference: " + timeDiff + " seconds");
            if (Math.abs(timeDiff) > 3) {
                if (Math.abs(timeDiff) > 60 * 60 * 1.5) {
                    if (L.isEnabled(L.PUMP))
                        log.debug("Pump time difference: " + timeDiff + " seconds - large difference");
                    //If time-diff is very large, warn user until we can synchronize history readings properly
                    Intent i = new Intent(MainApp.instance(), ErrorHelperActivity.class);
                    i.putExtra("soundid", R.raw.error);
                    i.putExtra("status", MainApp.gs(R.string.largetimediff));
                    i.putExtra("title", MainApp.gs(R.string.largetimedifftitle));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    MainApp.instance().startActivity(i);

                    //deinitialize pump
                    mDanaRPump.lastConnection = 0;
                    MainApp.bus().post(new EventDanaRNewStatus());
                    MainApp.bus().post(new EventInitializationChanged());
                    return;
                } else {
                    waitForWholeMinute(); // Dana can set only whole minute
                    // add 10sec to be sure we are over minute (will be cutted off anyway)
                    mSerialIOThread.sendMessage(new MsgSetTime(new Date(DateUtil.now() + T.secs(10).msecs())));
                    mSerialIOThread.sendMessage(new MsgSettingPumpTime());
                    timeDiff = (mDanaRPump.pumpTime - System.currentTimeMillis()) / 1000L;
                    if (L.isEnabled(L.PUMP))
                        log.debug("Pump time difference: " + timeDiff + " seconds");
                }
            }

            long now = System.currentTimeMillis();
            if (mDanaRPump.lastSettingsRead + 60 * 60 * 1000L < now || !pump.isInitialized()) {
                MainApp.bus().post(new EventPumpStatusChanged(MainApp.gs(R.string.gettingpumpsettings)));
                mSerialIOThread.sendMessage(new MsgSettingShippingInfo());
                mSerialIOThread.sendMessage(new MsgSettingActiveProfile());
                mSerialIOThread.sendMessage(new MsgSettingMeal());
                mSerialIOThread.sendMessage(new MsgSettingBasal());
                //0x3201
                mSerialIOThread.sendMessage(new MsgSettingMaxValues());
                mSerialIOThread.sendMessage(new MsgSettingGlucose());
                mSerialIOThread.sendMessage(new MsgSettingActiveProfile());
                mSerialIOThread.sendMessage(new MsgSettingProfileRatios());
                mSerialIOThread.sendMessage(new MsgSettingUserOptions());
                mSerialIOThread.sendMessage(new MsgSettingProfileRatiosAll());
                mDanaRPump.lastSettingsRead = now;
            }

            loadEvents();

            MainApp.bus().post(new EventDanaRNewStatus());
            MainApp.bus().post(new EventInitializationChanged());
            NSUpload.uploadDeviceStatus();
            if (mDanaRPump.dailyTotalUnits > mDanaRPump.maxDailyTotalUnits * Constants.dailyLimitWarning) {
                if (L.isEnabled(L.PUMP))
                    log.debug("Approaching daily limit: " + mDanaRPump.dailyTotalUnits + "/" + mDanaRPump.maxDailyTotalUnits);
                if (System.currentTimeMillis() > lastApproachingDailyLimit + 30 * 60 * 1000) {
                    Notification reportFail = new Notification(Notification.APPROACHING_DAILY_LIMIT, MainApp.gs(R.string.approachingdailylimit), Notification.URGENT);
                    MainApp.bus().post(new EventNewNotification(reportFail));
                    NSUpload.uploadError(MainApp.gs(R.string.approachingdailylimit) + ": " + mDanaRPump.dailyTotalUnits + "/" + mDanaRPump.maxDailyTotalUnits + "U");
                    lastApproachingDailyLimit = System.currentTimeMillis();
                }
            }
        } catch (Exception e) {
            log.error("Unhandled exception", e);
        }
    }

    public boolean tempBasal(int percent, int durationInHours) {
        if (!isConnected()) return false;
        if (mDanaRPump.isTempBasalInProgress) {
            MainApp.bus().post(new EventPumpStatusChanged(MainApp.gs(R.string.stoppingtempbasal)));
            mSerialIOThread.sendMessage(new MsgSetTempBasalStop());
            SystemClock.sleep(500);
        }
        MainApp.bus().post(new EventPumpStatusChanged(MainApp.gs(R.string.settingtempbasal)));
        mSerialIOThread.sendMessage(new MsgSetTempBasalStart(percent, durationInHours));
        mSerialIOThread.sendMessage(new MsgStatusTempBasal_v2());
        loadEvents();
        MainApp.bus().post(new EventPumpStatusChanged(EventPumpStatusChanged.DISCONNECTING));
        return true;
    }

    public boolean highTempBasal(int percent) {
        if (!isConnected()) return false;
        if (mDanaRPump.isTempBasalInProgress) {
            MainApp.bus().post(new EventPumpStatusChanged(MainApp.gs(R.string.stoppingtempbasal)));
            mSerialIOThread.sendMessage(new MsgSetTempBasalStop());
            SystemClock.sleep(500);
        }
        MainApp.bus().post(new EventPumpStatusChanged(MainApp.gs(R.string.settingtempbasal)));
        mSerialIOThread.sendMessage(new MsgSetAPSTempBasalStart_v2(percent));
        mSerialIOThread.sendMessage(new MsgStatusTempBasal_v2());
        loadEvents();
        MainApp.bus().post(new EventPumpStatusChanged(EventPumpStatusChanged.DISCONNECTING));
        return true;
    }

    public boolean tempBasalShortDuration(int percent, int durationInMinutes) {
        if (durationInMinutes != 15 && durationInMinutes != 30) {
            log.error("Wrong duration param");
            return false;
        }

        if (!isConnected()) return false;
        if (mDanaRPump.isTempBasalInProgress) {
            MainApp.bus().post(new EventPumpStatusChanged(MainApp.gs(R.string.stoppingtempbasal)));
            mSerialIOThread.sendMessage(new MsgSetTempBasalStop());
            SystemClock.sleep(500);
        }
        MainApp.bus().post(new EventPumpStatusChanged(MainApp.gs(R.string.settingtempbasal)));
        mSerialIOThread.sendMessage(new MsgSetAPSTempBasalStart_v2(percent, durationInMinutes == 15, durationInMinutes == 30));
        mSerialIOThread.sendMessage(new MsgStatusTempBasal_v2());
        loadEvents();
        MainApp.bus().post(new EventPumpStatusChanged(EventPumpStatusChanged.DISCONNECTING));
        return true;
    }

    public boolean tempBasalStop() {
        if (!isConnected()) return false;
        MainApp.bus().post(new EventPumpStatusChanged(MainApp.gs(R.string.stoppingtempbasal)));
        mSerialIOThread.sendMessage(new MsgSetTempBasalStop());
        mSerialIOThread.sendMessage(new MsgStatusTempBasal_v2());
        loadEvents();
        MainApp.bus().post(new EventPumpStatusChanged(EventPumpStatusChanged.DISCONNECTING));
        return true;
    }

    public boolean extendedBolus(double insulin, int durationInHalfHours) {
        if (!isConnected()) return false;
        MainApp.bus().post(new EventPumpStatusChanged(MainApp.gs(R.string.settingextendedbolus)));
        mSerialIOThread.sendMessage(new MsgSetExtendedBolusStart(insulin, (byte) (durationInHalfHours & 0xFF)));
        mSerialIOThread.sendMessage(new MsgStatusBolusExtended_v2());
        loadEvents();
        MainApp.bus().post(new EventPumpStatusChanged(EventPumpStatusChanged.DISCONNECTING));
        return true;
    }

    public boolean extendedBolusStop() {
        if (!isConnected()) return false;
        MainApp.bus().post(new EventPumpStatusChanged(MainApp.gs(R.string.stoppingextendedbolus)));
        mSerialIOThread.sendMessage(new MsgSetExtendedBolusStop());
        mSerialIOThread.sendMessage(new MsgStatusBolusExtended_v2());
        loadEvents();
        MainApp.bus().post(new EventPumpStatusChanged(EventPumpStatusChanged.DISCONNECTING));
        return true;
    }

    public boolean bolus(final double amount, int carbs, long carbtime, final Treatment t) {
        if (!isConnected()) return false;
        if (BolusProgressDialog.stopPressed) return false;

        MainApp.bus().post(new EventPumpStatusChanged(MainApp.gs(R.string.startingbolus)));
        mBolusingTreatment = t;
        final int preferencesSpeed = SP.getInt(R.string.key_danars_bolusspeed, 0);
        MessageBase start;
        if (preferencesSpeed == 0)
            start = new MsgBolusStart(amount);
        else
            start = new MsgBolusStartWithSpeed(amount, preferencesSpeed);
        MsgBolusStop stop = new MsgBolusStop(amount, t);

        if (carbs > 0) {
            MsgSetCarbsEntry msg = new MsgSetCarbsEntry(carbtime, carbs);
            mSerialIOThread.sendMessage(msg);
            MsgSetHistoryEntry_v2 msgSetHistoryEntry_v2 = new MsgSetHistoryEntry_v2(DanaRPump.CARBS, carbtime, carbs, 0);
            mSerialIOThread.sendMessage(msgSetHistoryEntry_v2);
            lastHistoryFetched = Math.min(lastHistoryFetched, carbtime - T.mins(1).msecs());
        }

        final long bolusStart = System.currentTimeMillis();
        if (amount > 0) {
            MsgBolusProgress progress = new MsgBolusProgress(amount, t); // initialize static variables

            if (!stop.stopped) {
                mSerialIOThread.sendMessage(start);
            } else {
                t.insulin = 0d;
                return false;
            }
            while (!stop.stopped && !start.failed) {
                SystemClock.sleep(100);
                if ((System.currentTimeMillis() - progress.lastReceive) > 15 * 1000L) { // if i didn't receive status for more than 15 sec expecting broken comm
                    stop.stopped = true;
                    stop.forced = true;
                    log.error("Communication stopped");
                }
            }
        }

        final EventOverviewBolusProgress bolusingEvent = EventOverviewBolusProgress.getInstance();
        bolusingEvent.t = t;
        bolusingEvent.percent = 99;

        mBolusingTreatment = null;
        int speed = 12;
        switch (preferencesSpeed) {
            case 0:
                speed = 12;
                break;
            case 1:
                speed = 30;
                break;
            case 2:
                speed = 60;
                break;
        }
        long bolusDurationInMSec = (long) (amount * speed * 1000);
        long expectedEnd = bolusStart + bolusDurationInMSec + 2000;
        while (System.currentTimeMillis() < expectedEnd) {
            long waitTime = expectedEnd - System.currentTimeMillis();
            bolusingEvent.status = String.format(MainApp.gs(R.string.waitingforestimatedbolusend), waitTime / 1000);
            MainApp.bus().post(bolusingEvent);
            SystemClock.sleep(1000);
        }
        // do not call loadEvents() directly, reconnection may be needed
        ConfigBuilderPlugin.getPlugin().getCommandQueue().loadEvents(new Callback() {
            @Override
            public void run() {
                // load last bolus status
                MainApp.bus().post(new EventPumpStatusChanged(MainApp.gs(R.string.gettingbolusstatus)));
                mSerialIOThread.sendMessage(new MsgStatus());
                bolusingEvent.percent = 100;
                MainApp.bus().post(new EventPumpStatusChanged(MainApp.gs(R.string.disconnecting)));
            }
        });
        return !start.failed;
    }

    public void bolusStop() {
        if (L.isEnabled(L.PUMP))
            log.debug("bolusStop >>>>> @ " + (mBolusingTreatment == null ? "" : mBolusingTreatment.insulin));
        MsgBolusStop stop = new MsgBolusStop();
        stop.forced = true;
        if (isConnected()) {
            mSerialIOThread.sendMessage(stop);
            while (!stop.stopped) {
                mSerialIOThread.sendMessage(stop);
                SystemClock.sleep(200);
            }
        } else {
            stop.stopped = true;
        }
    }

    public boolean carbsEntry(int amount, long time) {
        if (!isConnected()) return false;
        MsgSetCarbsEntry msg = new MsgSetCarbsEntry(time, amount);
        mSerialIOThread.sendMessage(msg);
        MsgSetHistoryEntry_v2 msgSetHistoryEntry_v2 = new MsgSetHistoryEntry_v2(DanaRPump.CARBS, time, amount, 0);
        mSerialIOThread.sendMessage(msgSetHistoryEntry_v2);
        lastHistoryFetched = Math.min(lastHistoryFetched, time - T.mins(1).msecs());
        return true;
    }

    public PumpEnactResult loadEvents() {

        if (!MainApp.getSpecificPlugin(DanaRv2Plugin.class).isInitialized()) {
            PumpEnactResult result = new PumpEnactResult().success(false);
            result.comment = "pump not initialized";
            return result;
        }


        if (!isConnected())
            return new PumpEnactResult().success(false);
        SystemClock.sleep(300);
        MsgHistoryEvents_v2 msg;
        if (lastHistoryFetched == 0) {
            msg = new MsgHistoryEvents_v2();
            if (L.isEnabled(L.PUMP))
                log.debug("Loading complete event history");
        } else {
            msg = new MsgHistoryEvents_v2(lastHistoryFetched);
            if (L.isEnabled(L.PUMP))
                log.debug("Loading event history from: " + DateUtil.dateAndTimeFullString(lastHistoryFetched));
        }
        mSerialIOThread.sendMessage(msg);
        while (!msg.done && mRfcommSocket.isConnected()) {
            SystemClock.sleep(100);
        }
        SystemClock.sleep(200);
        if (MsgHistoryEvents_v2.lastEventTimeLoaded != 0)
            lastHistoryFetched = MsgHistoryEvents_v2.lastEventTimeLoaded - T.mins(1).msecs();
        else
            lastHistoryFetched = 0;
        mDanaRPump.lastConnection = System.currentTimeMillis();
        return new PumpEnactResult().success(true);
    }

    public boolean updateBasalsInPump(final Profile profile) {
        if (!isConnected()) return false;
        MainApp.bus().post(new EventPumpStatusChanged(MainApp.gs(R.string.updatingbasalrates)));
        double[] basal = DanaRPump.getInstance().buildDanaRProfileRecord(profile);
        MsgSetBasalProfile msgSet = new MsgSetBasalProfile((byte) 0, basal);
        mSerialIOThread.sendMessage(msgSet);
        MsgSetActivateBasalProfile msgActivate = new MsgSetActivateBasalProfile((byte) 0);
        mSerialIOThread.sendMessage(msgActivate);
        mDanaRPump.lastSettingsRead = 0; // force read full settings
        getPumpStatus();
        MainApp.bus().post(new EventPumpStatusChanged(EventPumpStatusChanged.DISCONNECTING));
        return true;
    }

    void waitForWholeMinute() {
        while (true) {
            long time = DateUtil.now();
            long timeToWholeMinute = (60000 - time % 60000);
            if (timeToWholeMinute > 59800 || timeToWholeMinute < 3000)
                break;
            MainApp.bus().post(new EventPumpStatusChanged(MainApp.gs(R.string.waitingfortimesynchronization, (int) (timeToWholeMinute / 1000))));
            SystemClock.sleep(Math.min(timeToWholeMinute, 100));
        }
    }

    public PumpEnactResult setUserOptions() {
        if (!isConnected())
            return new PumpEnactResult().success(false);
        SystemClock.sleep(300);
        MsgSetUserOptions msg = new MsgSetUserOptions();
        mSerialIOThread.sendMessage(msg);
        SystemClock.sleep(200);
        return new PumpEnactResult().success(!msg.failed);
    }

}
