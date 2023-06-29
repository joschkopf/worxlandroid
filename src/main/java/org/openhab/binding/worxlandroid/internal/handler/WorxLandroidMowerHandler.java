/**
 * Copyright (c) 2010-2023 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.worxlandroid.internal.handler;

import static org.openhab.binding.worxlandroid.internal.ChannelTypeUtils.toQuantityType;
import static org.openhab.binding.worxlandroid.internal.WorxLandroidBindingConstants.*;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.measure.Unit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.worxlandroid.internal.api.WebApiException;
import org.openhab.binding.worxlandroid.internal.api.WorxApiDeserializer;
import org.openhab.binding.worxlandroid.internal.api.dto.ProductItemStatus;
import org.openhab.binding.worxlandroid.internal.api.dto.ProductItemStatus.Battery;
import org.openhab.binding.worxlandroid.internal.api.dto.ProductItemStatus.Cfg;
import org.openhab.binding.worxlandroid.internal.api.dto.ProductItemStatus.Dat;
import org.openhab.binding.worxlandroid.internal.api.dto.ProductItemStatus.Ots;
import org.openhab.binding.worxlandroid.internal.api.dto.ProductItemStatus.Payload;
import org.openhab.binding.worxlandroid.internal.api.dto.ProductItemStatus.Rain;
import org.openhab.binding.worxlandroid.internal.api.dto.ProductItemStatus.Schedule;
import org.openhab.binding.worxlandroid.internal.api.dto.ProductItemStatus.St;
import org.openhab.binding.worxlandroid.internal.codes.WorxLandroidActionCodes;
import org.openhab.binding.worxlandroid.internal.codes.WorxLandroidDayCodes;
import org.openhab.binding.worxlandroid.internal.codes.WorxLandroidStatusCodes;
import org.openhab.binding.worxlandroid.internal.config.MowerConfiguration;
import org.openhab.binding.worxlandroid.internal.mqtt.AWSException;
import org.openhab.binding.worxlandroid.internal.mqtt.AWSMessage;
import org.openhab.binding.worxlandroid.internal.mqtt.AWSMessageCallback;
import org.openhab.binding.worxlandroid.internal.mqtt.AWSTopic;
import org.openhab.binding.worxlandroid.internal.vo.Mower;
import org.openhab.binding.worxlandroid.internal.vo.ScheduledDay;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.unit.SIUnits;
import org.openhab.core.library.unit.Units;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.thing.binding.builder.ThingBuilder;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The{@link WorxLandroidMowerHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author Nils - Initial contribution
 *
 */
@NonNullByDefault
public class WorxLandroidMowerHandler extends BaseThingHandler implements AWSMessageCallback {
    record ZoneMeterCommand(int[] mz) {
    }

    record ZoneMeterAlloc(int[] mzv) {
    }

    record ScheduleCommandMode(int m) {

    }

    record ScheduleCommand(ScheduleCommandMode sc) {

        public ScheduleCommand(int m) {
            this(new ScheduleCommandMode(m));
        }
    }

    record ScheduleDaysP(int p, Object d, @Nullable Object dd) {
    }

    record ScheduleDaysCommand(ScheduleDaysP sc) {

        public ScheduleDaysCommand(int p, Object[] d, Object[] dd) {
            this(new ScheduleDaysP(p, d, dd));
        }

        public ScheduleDaysCommand(int p, Object[] d) {
            this(new ScheduleDaysP(p, d, null));
        }
    }

    record OTS(OTSCommand ots) {

    }

    record OTSCommand(int bc, int wtm) {
        // bc = bordercut
        // wtm = work time minutes
    }

    record OneTimeCommand(OTS sc) {
        public OneTimeCommand(int bc, int wtm) {
            this(new OTS(new OTSCommand(bc, wtm)));
        }
    }

    private final Logger logger = LoggerFactory.getLogger(WorxLandroidMowerHandler.class);
    private final WorxApiDeserializer deserializer;

    private Optional<AWSTopic> awsTopic = Optional.empty();
    private Optional<Mower> mower = Optional.empty();
    private Optional<ScheduledFuture<?>> refreshJob = Optional.empty();
    private Optional<ScheduledFuture<?>> pollingJob = Optional.empty();

    private boolean restoreZoneMeter = false;
    private int[] zoneMeterRestoreValues = {};

    public WorxLandroidMowerHandler(Thing thing, WorxApiDeserializer deserializer) {
        super(thing);
        this.deserializer = deserializer;
    }

    @Override
    public void initialize() {
        MowerConfiguration config = getConfigAs(MowerConfiguration.class);

        if (config.serialNumber.isBlank()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "@text/conf-error-no-serial");
            return;
        }

        WorxLandroidBridgeHandler bridgeHandler = getWorxLandroidBridgeHandler();
        if (bridgeHandler != null) {
            logger.debug("Initializing WorxLandroidMowerHandler for serial number '{}'", config.serialNumber);
            try {
                ProductItemStatus product = bridgeHandler.retrieveDeviceStatus(config.serialNumber);
                if (product != null) {
                    Mower theMower = new Mower(product);
                    ThingUID thingUid = thing.getUID();
                    ThingBuilder thingBuilder = editThing();

                    if (!theMower.lockSupported()) { // lock channel only when supported
                        thingBuilder.withoutChannel(new ChannelUID(thingUid, GROUP_COMMON, CHANNEL_LOCK));
                    }

                    if (!theMower.rainDelaySupported()) { // rainDelay channel only when supported
                        thingBuilder.withoutChannel(new ChannelUID(thingUid, GROUP_RAIN, CHANNEL_DELAY));
                    }

                    if (!theMower.rainDelayStartSupported()) { // // rainDelayStart channel only when supported
                        thingBuilder.withoutChannel(new ChannelUID(thingUid, GROUP_RAIN, CHANNEL_RAIN_STATE));
                        thingBuilder.withoutChannel(new ChannelUID(thingUid, GROUP_RAIN, CHANNEL_RAIN_COUNTER));
                    }

                    if (!theMower.multiZoneSupported()) { // multizone channels only when supported
                        // remove lastZome channel
                        thingBuilder.withoutChannel(new ChannelUID(thingUid, GROUP_RAIN, CHANNEL_LAST_ZONE));
                        // remove zone meter channels
                        for (int zoneIndex = 0; zoneIndex < theMower.getMultiZoneCount(); zoneIndex++) {
                            thingBuilder.withoutChannel(
                                    new ChannelUID(thingUid, GROUP_MULTI_ZONES, "zone-%d".formatted(zoneIndex + 1)));
                        }
                        // remove allocation channels
                        for (int allocationIndex = 0; allocationIndex < 10; allocationIndex++) {
                            thingBuilder.withoutChannel(new ChannelUID(thingUid, GROUP_MULTI_ZONES,
                                    "%s-%d".formatted(CHANNEL_PREFIX_ALLOCATION, allocationIndex)));
                        }
                    }

                    if (!theMower.oneTimeSchedulerSupported()) { // oneTimeScheduler channel only when supported
                        thingBuilder.withoutChannel(new ChannelUID(thingUid, GROUP_ONE_TIME, CHANNEL_DURATION));
                        thingBuilder.withoutChannel(new ChannelUID(thingUid, GROUP_ONE_TIME, CHANNEL_EDGECUT));
                        thingBuilder.withoutChannel(new ChannelUID(thingUid, GROUP_ONE_TIME, CHANNEL_MODE));
                    }

                    if (!theMower.scheduler2Supported()) { // Scheduler 2 channels only when supported version
                        for (WorxLandroidDayCodes dayCode : WorxLandroidDayCodes.values()) {
                            String groupName = "%s2".formatted(dayCode.getDescription());
                            thingBuilder.withoutChannel(new ChannelUID(thingUid, groupName, CHANNEL_ENABLE));
                            thingBuilder.withoutChannel(new ChannelUID(thingUid, groupName, CHANNEL_START_HOUR));
                            thingBuilder.withoutChannel(new ChannelUID(thingUid, groupName, CHANNEL_START_MINUTES));
                            thingBuilder.withoutChannel(new ChannelUID(thingUid, groupName, CHANNEL_DURATION));
                            thingBuilder.withoutChannel(new ChannelUID(thingUid, groupName, CHANNEL_EDGECUT));
                        }
                    }

                    updateThing(thingBuilder.build());

                    processStatusMessage(theMower, product);

                    AWSTopic commandOutTopic = new AWSTopic(theMower.getMqttCommandOut(), this);
                    bridgeHandler.subcribeTopic(commandOutTopic);
                    bridgeHandler.publishMessage(theMower.getMqttCommandIn(), AWSMessage.EMPTY_PAYLOAD);

                    mower = Optional.of(theMower);
                    awsTopic = Optional.of(commandOutTopic);
                    updateStatus(product.online ? ThingStatus.ONLINE : ThingStatus.OFFLINE);
                    startScheduledJobs(bridgeHandler, theMower);
                }
            } catch (WebApiException e) {
                logger.error("initialize mower: id {} - {}::{}", config.serialNumber, getThing().getLabel(),
                        getThing().getUID());
            }
            updateStatus(ThingStatus.ONLINE);
        } else {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.OFFLINE.BRIDGE_OFFLINE);
        }
    }

    private void processStatusMessage(Mower theMower, ProductItemStatus product) {
        processStatusMessage(theMower, product.lastStatus.payload);
        initializeProperties(product);
    }

    private void processStatusMessage(Mower theMower, Payload payload) {
        updateStateCfg(theMower, payload.cfg);
        updateStateDat(theMower, payload.dat);
    }

    void initializeProperties(ProductItemStatus product) {
        Map<String, String> properties = editProperties();
        properties.put(Thing.PROPERTY_SERIAL_NUMBER, product.serialNumber);
        properties.put(Thing.PROPERTY_FIRMWARE_VERSION, Double.toString(product.firmwareVersion));
        properties.put(Thing.PROPERTY_MAC_ADDRESS, product.macAddress);
        properties.put(Thing.PROPERTY_VENDOR, "Worx");
        properties.put("productId", product.id);
        properties.put("language", product.lastStatus.payload.cfg.lg);
        updateProperties(properties);
    }

    /**
     * Start scheduled jobs.
     * Jobs are only started if interval > 0
     *
     * @param bridgeHandler
     *
     * @param theMower
     */
    private void startScheduledJobs(WorxLandroidBridgeHandler bridgeHandler, Mower theMower) {
        MowerConfiguration config = getConfigAs(MowerConfiguration.class);

        if (config.refreshStatusInterval > 0) {
            refreshJob = Optional.of(scheduler.scheduleWithFixedDelay(() -> {
                try {
                    ProductItemStatus product = bridgeHandler.retrieveDeviceStatus(config.serialNumber);
                    updateChannelDateTime(GROUP_COMMON, CHANNEL_ONLINE_TIMESTAMP, ZonedDateTime.now());
                    updateChannelOnOff(GROUP_COMMON, CHANNEL_ONLINE, product != null && product.online);
                    updateStatus(product != null ? ThingStatus.ONLINE : ThingStatus.OFFLINE);
                } catch (WebApiException e) {
                    logger.debug("Refreshing Thing {} failed, handler might be OFFLINE", config.serialNumber);
                }
            }, 3, config.refreshStatusInterval, TimeUnit.SECONDS));
        }

        if (config.pollingInterval > 0) {
            pollingJob = Optional.of(scheduler.scheduleWithFixedDelay(() -> {
                bridgeHandler.publishMessage(theMower.getMqttCommandIn(), AWSMessage.EMPTY_PAYLOAD);
                logger.debug("send polling message");
            }, 5, config.pollingInterval, TimeUnit.SECONDS));
        }
    }

    /**
     * @return
     */
    private synchronized @Nullable WorxLandroidBridgeHandler getWorxLandroidBridgeHandler() {
        Bridge bridge = getBridge();
        if (bridge != null && bridge.getHandler() instanceof WorxLandroidBridgeHandler bridgeHandler
                && bridgeHandler.isBridgeOnline()) {
            return bridgeHandler;
        }
        return null;
    }

    @Override
    public void dispose() {
        refreshJob.ifPresent(job -> job.cancel(true));
        refreshJob = Optional.empty();

        pollingJob.ifPresent(job -> job.cancel(true));
        pollingJob = Optional.empty();
    }

    @Override
    public void bridgeStatusChanged(ThingStatusInfo bridgeStatusInfo) {
        // TODO NB workaround reconnect nötig???
        WorxLandroidBridgeHandler bridgeHandler = getWorxLandroidBridgeHandler();
        if (ThingStatus.ONLINE.equals(bridgeStatusInfo.getStatus()) && bridgeHandler != null) {
            initialize();
            // awsTopic = new AWSTopic(awsTopic.getTopic(), this);
            awsTopic.ifPresent(topic -> bridgeHandler.subcribeTopic(topic));
        }
        super.bridgeStatusChanged(bridgeStatusInfo);
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (command instanceof RefreshType) {
            return;
        }

        if (getThing().getStatus() != ThingStatus.ONLINE) {
            logger.error("handleCommand mower: {} is offline!", getThing().getUID());
            return;
        }

        WorxLandroidBridgeHandler bridgeHandler = getWorxLandroidBridgeHandler();
        if (bridgeHandler == null) {
            logger.error("no bridgeHandler");
            return;
        }

        mower.ifPresent(theMower -> {
            if (GROUP_MULTI_ZONES.equals(channelUID.getGroupId())) {
                handleMultiZonesCommand(theMower, channelUID.getIdWithoutGroup(), command);
            } else if (GROUP_SCHEDULE.equals(channelUID.getGroupId())) {
                handleScheduleCommand(theMower, channelUID.getIdWithoutGroup(), Integer.parseInt(command.toString()));
            } else if (GROUP_ONE_TIME.equals(channelUID.getGroupId())) {
                handleOneTimeSchedule(theMower, channelUID.getIdWithoutGroup(), command);
            }
            if (CHANNEL_LAST_ZONE.equals(channelUID.getId())) {
                if (!WorxLandroidStatusCodes.HOME.equals(theMower.getStatus())) {
                    logger.warn("Cannot start zone because mower must be at HOME!");
                    return;
                }
                zoneMeterRestoreValues = theMower.getZoneMeters();
                restoreZoneMeter = true;

                int meter = theMower.getZoneMeter(Integer.parseInt(command.toString()));
                for (int zoneIndex = 0; zoneIndex < 4; zoneIndex++) {
                    theMower.setZoneMeter(zoneIndex, meter);
                }
                sendCommand(theMower, new ZoneMeterCommand(theMower.getZoneMeters()));
                scheduler.schedule(() -> sendCommand(theMower, AWSMessage.CMD_START), 2000, TimeUnit.MILLISECONDS);
                return;
            }
            // channel: multizone allocation (mzv)
            // update schedule
            // TODO ugly check
            if (CHANNEL_ENABLE.equals(channelUID.getIdWithoutGroup())
                    || channelUID.getGroupId().equals(GROUP_SCHEDULE)) {
                // update mower data

                // update enable mowing or schedule or timeExtension/enable?
                if (CHANNEL_ENABLE.equals(channelUID.getIdWithoutGroup())) {
                    theMower.setEnable(OnOffType.ON.equals(command));
                } else {
                    setScheduledDays(theMower, channelUID, command);
                }

                sendCommand(theMower,
                        theMower.scheduler2Supported()
                                ? new ScheduleDaysCommand(theMower.getTimeExtension(), theMower.getSheduleArray1(),
                                        theMower.getSheduleArray2())
                                : new ScheduleDaysCommand(theMower.getTimeExtension(), theMower.getSheduleArray1()));

                return;
            }

            String cmd = AWSMessage.EMPTY_PAYLOAD;

            switch (channelUID.getIdWithoutGroup()) {
                // start action
                case CHANNEL_ACTION:
                    WorxLandroidActionCodes actionCode = WorxLandroidActionCodes.valueOf(command.toString());
                    logger.debug("{}", actionCode.toString());
                    cmd = "{\"cmd\":%s}".formatted(actionCode.code);
                    break;

                // poll
                case CHANNEL_POLL:
                    cmd = AWSMessage.EMPTY_PAYLOAD;
                    updateState(CHANNEL_POLL, OnOffType.OFF);
                    break;

                // update rainDelay
                case CHANNEL_DELAY:
                    cmd = "{\"rd\":%s}".formatted(command);
                    break;

                // lock/unlock
                case CHANNEL_LOCK:
                    WorxLandroidActionCodes lockCode = OnOffType.ON.equals(command) ? WorxLandroidActionCodes.LOCK
                            : WorxLandroidActionCodes.UNLOCK;
                    logger.debug("{}", lockCode.toString());
                    cmd = "{\"cmd\":%s}".formatted(lockCode.code);
                    break;

                default:
                    logger.debug("command for ChannelUID not supported: {}", channelUID.getAsString());
                    break;
            }
            sendCommand(theMower, cmd);
        });
    }

    private void handleOneTimeSchedule(Mower theMower, String channel, Command command) {
        if (CHANNEL_DURATION.equals(channel)) {
            sendCommand(theMower, new OneTimeCommand(0, Integer.parseInt(command.toString())));
        } else if (CHANNEL_EDGECUT.equals(channel)) {
            sendCommand(theMower, new OneTimeCommand(OnOffType.ON.equals(command) ? 1 : 0, 0));
        } else {
            logger.warn("No action identified for command {} on channel {}", command, channel);
            return;
        }
    }

    private void handleScheduleCommand(Mower theMower, String channel, int command) {
        if (CHANNEL_MODE.equals(channel)) {
            sendCommand(theMower, new ScheduleCommand(command));
        } else if (CHANNEL_TIME_EXTENSION.equals(channel)) {
            theMower.setTimeExtension(command);
            sendCommand(theMower,
                    theMower.scheduler2Supported()
                            ? new ScheduleDaysCommand(theMower.getTimeExtension(), theMower.getSheduleArray1(),
                                    theMower.getSheduleArray2())
                            : new ScheduleDaysCommand(theMower.getTimeExtension(), theMower.getSheduleArray1()));
        }
    }

    private void handleMultiZonesCommand(Mower theMower, String channel, Command command) {
        Object mqttCommand;
        if (CHANNEL_ENABLE.equals(channel)) {
            theMower.setMultiZoneEnable(OnOffType.ON.equals(command));
            mqttCommand = new ZoneMeterCommand(theMower.getZoneMeters());
        } else {
            String[] names = channel.split("-");
            int index = Integer.valueOf(names[1]);

            if (CHANNEL_PREFIX_ZONE.equals(names[0])) {
                int meterValue = command instanceof QuantityType qtty ? qtty.toUnit(SIUnits.METRE).intValue()
                        : Integer.parseInt(command.toString());

                theMower.setZoneMeter(index - 1, meterValue);
                mqttCommand = new ZoneMeterCommand(theMower.getZoneMeters());

            } else if (CHANNEL_PREFIX_ALLOCATION.equals(names[0])) {
                theMower.setAllocation(index, Integer.parseInt(command.toString()));
                mqttCommand = new ZoneMeterAlloc(theMower.getAllocations());
            } else {
                logger.warn("No action identified for command {} on channel {}", command, channel);
                return;
            }
        }
        sendCommand(theMower, mqttCommand);
    }

    /**
     * Set scheduled days
     *
     * @param theMower
     *
     * @param scDaysIndex 1 or 2
     * @param channelUID
     * @param command
     */
    private void setScheduledDays(Mower theMower, ChannelUID channelUID, Command command) {
        // extract name of from channel
        Pattern pattern = Pattern.compile("cfgSc(.*?)#");
        Matcher matcher = pattern.matcher(channelUID.getId());

        int scDaysSlot = 1;
        String day = "";
        if (matcher.find()) {
            day = (matcher.group(1));
            scDaysSlot = day.endsWith("day") ? 1 : 2;
            day = scDaysSlot == 1 ? day : day.substring(0, day.length() - 1);
        }

        WorxLandroidDayCodes dayCodeUpdated = WorxLandroidDayCodes.valueOf(day.toUpperCase());

        ScheduledDay scheduledDayUpdated = theMower.getScheduledDay(scDaysSlot, dayCodeUpdated);
        if (scheduledDayUpdated == null) {
            return;
        }

        String chName = channelUID.getIdWithoutGroup();
        switch (chName) {
            case CHANNEL_ENABLE:
                scheduledDayUpdated.setEnable(OnOffType.ON.equals(command));
                break;

            case CHANNEL_START_HOUR:
                scheduledDayUpdated.setHours(Integer.parseInt(command.toString()));
                break;

            case CHANNEL_START_MINUTES:
                scheduledDayUpdated.setMinutes(Integer.parseInt(command.toString()));
                break;

            case CHANNEL_DURATION:
                scheduledDayUpdated.setDuration(Integer.parseInt(command.toString()));
                break;

            case CHANNEL_EDGECUT:
                scheduledDayUpdated.setEdgecut(OnOffType.ON.equals(command));
                break;

            default:
                break;
        }
    }

    /**
     * Send given command.
     *
     * @param theMower
     *
     * @param cmd
     * @throws AWSException
     * @throws AWSIotException
     */
    private void sendCommand(Mower theMower, String cmd) {
        logger.debug("send command: {}", cmd);

        WorxLandroidBridgeHandler bridgeHandler = getWorxLandroidBridgeHandler();
        if (bridgeHandler != null) {
            bridgeHandler.publishMessage(theMower.getMqttCommandIn(), cmd);
        }
    }

    private void sendCommand(Mower theMower, Object command) {
        logger.debug("send command: {}", command);

        WorxLandroidBridgeHandler bridgeHandler = getWorxLandroidBridgeHandler();
        if (bridgeHandler != null) {
            bridgeHandler.publishMessage(theMower.getMqttCommandIn(), command);
        }
    }

    @Override
    public void processMessage(AWSMessage message) {
        updateStatus(ThingStatus.ONLINE);

        try {
            Payload payload = deserializer.deserialize(Payload.class, message.payload());
            mower.ifPresent(theMower -> processStatusMessage(theMower, payload));
        } catch (WebApiException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    /**
     * Update states for data values
     *
     * @param theMower
     *
     * @param dat
     */
    private void updateStateDat(Mower theMower, Dat dat) {
        ThingUID thingUid = thing.getUID();
        // updateState(CHANNEL_MAC_ADRESS, dat.mac.isEmpty() ? UnDefType.NULL : new StringType(dat.mac));
        updateChannelString(GROUP_GENERAL, CHANNEL_FIRMWARE, dat.fw.isEmpty() ? null : dat.fw);

        if (dat.battery != null) {
            Battery battery = dat.battery;
            updateState(new ChannelUID(thingUid, GROUP_BATTERY, CHANNEL_TEMPERATURE),
                    battery.temperature != -1 ? toQuantityType(battery.temperature, SIUnits.CELSIUS) : UnDefType.NULL);
            updateState(new ChannelUID(thingUid, GROUP_BATTERY, CHANNEL_VOLTAGE),
                    battery.voltage != -1 ? toQuantityType(battery.voltage, Units.VOLT) : UnDefType.NULL);
            updateChannelDecimal(GROUP_BATTERY, CHANNEL_LEVEL, battery.level != -1 ? battery.level : null);
            updateChannelDecimal(GROUP_BATTERY, CHANNEL_CHARGE_CYCLE,
                    battery.chargeCycle != -1 ? battery.chargeCycle : null);

            long batteryChargeCyclesCurrent = battery.chargeCycle;
            String batteryChargeCyclesReset = getThing().getProperties().get("battery_charge_cycles_reset");
            if (batteryChargeCyclesReset != null && !batteryChargeCyclesReset.isEmpty()) {
                batteryChargeCyclesCurrent = battery.chargeCycle - Long.valueOf(batteryChargeCyclesReset);
            }
            updateState(new ChannelUID(thingUid, GROUP_BATTERY, CHANNEL_CHARGE_CYCLE_CURRENT),
                    new DecimalType(batteryChargeCyclesCurrent));
            updateChannelOnOff(GROUP_BATTERY, CHANNEL_CHARGING, battery.charging);
        }

        updateChannelQuantity(GROUP_ORIENTATION, CHANNEL_PITCH, dat.dataMotionProcessor[0], Units.DEGREE_ANGLE);
        updateChannelQuantity(GROUP_ORIENTATION, CHANNEL_ROLL, dat.dataMotionProcessor[1], Units.DEGREE_ANGLE);
        updateChannelQuantity(GROUP_ORIENTATION, CHANNEL_YAW, dat.dataMotionProcessor[2], Units.DEGREE_ANGLE);

        // dat/st
        if (dat.st != null) {
            St st = dat.st;
            // dat/st/b -> totalBladeTime
            if (st.totalBladeTime != -1) {
                updateState(new ChannelUID(thingUid, GROUP_METRICS, CHANNEL_TOTAL_BLADE_TIME),
                        toQuantityType(st.totalBladeTime, Units.MINUTE));

                long bladeTimeCurrent = st.totalBladeTime;
                String bladeWorkTimeReset = getThing().getProperties().get("blade_work_time_reset");
                if (bladeWorkTimeReset != null && !bladeWorkTimeReset.isEmpty()) {
                    bladeTimeCurrent = st.totalBladeTime - Long.valueOf(bladeWorkTimeReset);
                }
                updateState(new ChannelUID(thingUid, GROUP_METRICS, CHANNEL_CURRENT_BLADE_TIME),
                        toQuantityType(bladeTimeCurrent, Units.MINUTE));
            }

            updateState(new ChannelUID(thingUid, GROUP_METRICS, CHANNEL_TOTAL_DISTANCE),
                    st.totalDistance != -1 ? toQuantityType(st.totalDistance, SIUnits.METRE) : UnDefType.NULL);
            updateState(new ChannelUID(thingUid, GROUP_METRICS, CHANNEL_TOTAL_TIME),
                    st.totalTime != -1 ? toQuantityType(st.totalTime, Units.MINUTE) : UnDefType.NULL);
            // TODO dat/st/bl -> ?
        }
        // dat/ls -> statusCode
        theMower.setStatus(dat.statusCode);
        updateState(new ChannelUID(thingUid, GROUP_GENERAL, CHANNEL_STATUS_CODE),
                new StringType(dat.statusCode.name()));

        // restore
        if (restoreZoneMeter) {
            if (dat.statusCode != WorxLandroidStatusCodes.HOME
                    && dat.statusCode != WorxLandroidStatusCodes.START_SEQUENCE
                    && dat.statusCode != WorxLandroidStatusCodes.LEAVING_HOME
                    && dat.statusCode != WorxLandroidStatusCodes.SEARCHING_ZONE) {
                restoreZoneMeter = false;
                theMower.setZoneMeters(zoneMeterRestoreValues);
                sendCommand(theMower, new ZoneMeterCommand(theMower.getZoneMeters()));
            }
        }

        // dat/le -> errorCode
        updateState(new ChannelUID(thingUid, GROUP_GENERAL, CHANNEL_ERROR_CODE), new StringType(dat.errorCode.name()));

        // dat/lz -> lastZone
        int lastZone = theMower.getAllocation(dat.lastZone);
        updateState(new ChannelUID(thingUid, GROUP_GENERAL, CHANNEL_LAST_ZONE), new DecimalType(lastZone));

        int rssi = dat.wifiQuality;
        updateChannelDecimal(GROUP_WIFI, CHANNEL_WIFI_QUALITY, rssi <= 0 ? toQoS(rssi) : null);
        updateChannelQuantity(GROUP_WIFI, CHANNEL_RSSI,
                rssi <= 0 ? new QuantityType<>(rssi, Units.DECIBEL_MILLIWATTS) : null);

        if (theMower.lockSupported()) {
            updateChannelOnOff(GROUP_COMMON, CHANNEL_LOCK, dat.isLocked());
        }

        if (theMower.rainDelayStartSupported() && dat.rain instanceof Rain rain) {
            updateChannelOnOff(GROUP_RAIN, CHANNEL_RAIN_STATE, rain.raining);
            updateChannelDecimal(GROUP_RAIN, CHANNEL_RAIN_COUNTER, rain.counter);
        }
    }

    /**
     * Update states for cfg values
     *
     * @param theMower
     *
     * @param cfg
     * @param zoneId
     */
    private void updateStateCfg(Mower theMower, Cfg cfg) {
        updateChannelDateTime(GROUP_CONFIG, CHANNEL_TIMESTAMP, cfg.getDateTime(theMower.getZoneId()));

        if (cfg.sc instanceof Schedule sc) {
            if (theMower.oneTimeSchedulerSupported()) {
                updateChannelDecimal(GROUP_SCHEDULE, CHANNEL_MODE, sc.scheduleMode != -1 ? sc.scheduleMode : null);

                if (sc.ots instanceof Ots ots) {
                    updateChannelOnOff(GROUP_ONE_TIME, CHANNEL_EDGECUT, ots.getEdgeCut());
                    updateChannelDecimal(GROUP_ONE_TIME, CHANNEL_DURATION, ots.duration != -1 ? ots.duration : null);
                }
            }

            if (sc.timeExtension != -1) {
                theMower.setTimeExtension(sc.timeExtension);
                updateChannelQuantity(GROUP_SCHEDULE, CHANNEL_TIME_EXTENSION, sc.timeExtension, Units.PERCENT);
                updateChannelOnOff(GROUP_COMMON, CHANNEL_ENABLE, theMower.isEnable());
            }

            if (sc.d != null) {
                updateStateCfgScDays(theMower, 1, sc.d);
            }

            if (sc.dd != null) {
                updateStateCfgScDays(theMower, 2, sc.dd);
            }
        }

        // cfg/cmd -> command
        updateState(new ChannelUID(thing.getUID(), GROUP_CONFIG, CHANNEL_COMMAND),
                cfg.cmd != -1 ? new DecimalType(cfg.cmd) : UnDefType.NULL);

        if (theMower.multiZoneSupported()) {
            // zone meters
            if (!cfg.multiZones.isEmpty()) {
                for (int zoneIndex = 0; zoneIndex < 4; zoneIndex++) {
                    int meters = cfg.multiZones.get(zoneIndex);
                    theMower.setZoneMeter(zoneIndex, meters);
                    updateState("cfgMultiZones#zone%dMeter".formatted(zoneIndex + 1), new DecimalType(meters));
                }
            }

            // multizone enable is initialized and set by zone meters
            updateState(new ChannelUID(thing.getUID(), GROUP_MULTI_ZONES, CHANNEL_ENABLE),
                    OnOffType.from(theMower.isMultiZoneEnable()));

            // allocation zones
            if (!cfg.multizoneAllocations.isEmpty()) {
                for (int allocationIndex = 0; allocationIndex < 10; allocationIndex++) {
                    theMower.setAllocation(allocationIndex, cfg.multizoneAllocations.get(allocationIndex));
                    String channelNameAllocation = "%s-%d".formatted(CHANNEL_PREFIX_ALLOCATION, allocationIndex);
                    updateState(new ChannelUID(thing.getUID(), GROUP_MULTI_ZONES, channelNameAllocation),
                            new DecimalType(cfg.multizoneAllocations.get(allocationIndex)));
                }
            }
        }

        updateChannelQuantity(GROUP_RAIN, CHANNEL_DELAY,
                theMower.rainDelaySupported() && cfg.rainDelay != -1 ? cfg.rainDelay : null, Units.MINUTE);
    }

    /**
     * @param theMower
     * @param scDSlot scheduled day slot
     * @param d scheduled day JSON
     */
    private void updateStateCfgScDays(Mower theMower, int scDSlot, List<List<String>> d) {
        for (WorxLandroidDayCodes dayCode : WorxLandroidDayCodes.values()) {
            List<String> shedule = d.get(dayCode.code);

            ScheduledDay scheduledDay = theMower.getScheduledDay(scDSlot, dayCode);
            if (scheduledDay == null) {
                return;
            }

            String groupName = "%s%s".formatted(dayCode.getDescription().toLowerCase(),
                    scDSlot == 1 ? "" : String.valueOf(scDSlot));
            String time[] = shedule.get(0).split(":");

            scheduledDay.setHours(Integer.parseInt(time[0]));
            scheduledDay.setMinutes(Integer.parseInt(time[1]));
            scheduledDay.setDuration(Integer.valueOf(shedule.get(1)));
            scheduledDay.setEdgecut(Integer.valueOf(shedule.get(2)) == 1);

            updateChannelDecimal(groupName, CHANNEL_START_HOUR, scheduledDay.getHour());
            updateChannelDecimal(groupName, CHANNEL_START_MINUTES, scheduledDay.getMinutes());
            updateChannelQuantity(groupName, CHANNEL_DURATION, scheduledDay.getDuration(), Units.MINUTE);
            updateChannelOnOff(groupName, CHANNEL_ENABLE, scheduledDay.isEnable());
            updateChannelOnOff(groupName, CHANNEL_EDGECUT, scheduledDay.isEdgecut());
        }
    }

    private void updateIfActive(String group, String channelId, State state) {
        ChannelUID id = new ChannelUID(getThing().getUID(), group, channelId);
        if (isLinked(id)) {
            updateState(id, state);
        }
    }

    protected void updateChannelQuantity(String group, String channelId, @Nullable Number d, Unit<?> unit) {
        if (d == null) {
            updateIfActive(group, channelId, UnDefType.NULL);
        } else {
            updateChannelQuantity(group, channelId, new QuantityType<>(d, unit));
        }
    }

    protected void updateChannelQuantity(String group, String channelId, @Nullable QuantityType<?> quantity) {
        updateIfActive(group, channelId, quantity != null ? quantity : UnDefType.NULL);
    }

    protected void updateChannelOnOff(String group, String channelId, boolean value) {
        updateIfActive(group, channelId, OnOffType.from(value));
    }

    protected void updateChannelDateTime(String group, String channelId, @Nullable ZonedDateTime timestamp) {
        updateIfActive(group, channelId, timestamp == null ? UnDefType.NULL : new DateTimeType(timestamp));
    }

    protected void updateChannelString(String group, String channelId, @Nullable String value) {
        updateIfActive(group, channelId, value == null || value.isEmpty() ? UnDefType.NULL : new StringType(value));
    }

    protected void updateChannelDecimal(String group, String channelId, @Nullable Integer value) {
        updateIfActive(group, channelId, value == null || value == -1 ? UnDefType.NULL : new DecimalType(value));
    }

    private int toQoS(int rssi) {
        return rssi > -50 ? 4 : rssi > -60 ? 3 : rssi > -70 ? 2 : rssi > -85 ? 1 : 0;
    }
}
