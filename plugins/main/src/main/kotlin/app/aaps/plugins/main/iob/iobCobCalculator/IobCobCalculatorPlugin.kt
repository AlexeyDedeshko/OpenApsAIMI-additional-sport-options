package app.aaps.plugins.main.iob.iobCobCalculator

import androidx.collection.LongSparseArray
import app.aaps.annotations.OpenForTesting
import app.aaps.core.interfaces.aps.AutosensData
import app.aaps.core.interfaces.aps.AutosensDataStore
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.BasalData
import app.aaps.core.interfaces.configuration.Constants
import app.aaps.core.interfaces.iob.CobInfo
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.iob.IobTotal
import app.aaps.core.interfaces.iob.MealData
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.plugin.PluginType
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.Event
import app.aaps.core.interfaces.rx.events.EventConfigBuilderChange
import app.aaps.core.interfaces.rx.events.EventEffectiveProfileSwitchChanged
import app.aaps.core.interfaces.rx.events.EventNewBG
import app.aaps.core.interfaces.rx.events.EventNewHistoryData
import app.aaps.core.interfaces.rx.events.EventPreferenceChange
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.utils.MidnightTime
import app.aaps.core.interfaces.utils.T
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.main.extensions.convertedToAbsolute
import app.aaps.core.main.extensions.iobCalc
import app.aaps.core.main.extensions.toTemporaryBasal
import app.aaps.core.main.graph.OverviewData
import app.aaps.core.main.iob.combine
import app.aaps.core.main.iob.copy
import app.aaps.core.main.iob.determineBasalJson
import app.aaps.core.main.iob.plus
import app.aaps.core.main.iob.round
import app.aaps.core.main.workflow.CalculationWorkflow
import app.aaps.database.ValueWrapper
import app.aaps.database.entities.Bolus
import app.aaps.database.entities.ExtendedBolus
import app.aaps.database.entities.TemporaryBasal
import app.aaps.database.entities.UserEntry
import app.aaps.database.entities.interfaces.end
import app.aaps.database.impl.AppRepository
import app.aaps.plugins.main.R
import app.aaps.plugins.main.iob.iobCobCalculator.data.AutosensDataStoreObject
import dagger.android.HasAndroidInjector
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import org.json.JSONArray
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

@OpenForTesting
@Singleton
class IobCobCalculatorPlugin @Inject constructor(
    injector: HasAndroidInjector,
    aapsLogger: AAPSLogger,
    private val aapsSchedulers: AapsSchedulers,
    private val rxBus: RxBus,
    private val sp: SP,
    rh: ResourceHelper,
    private val profileFunction: ProfileFunction,
    private val activePlugin: ActivePlugin,
    private val fabricPrivacy: FabricPrivacy,
    private val dateUtil: DateUtil,
    private val repository: AppRepository,
    val overviewData: OverviewData,
    private val calculationWorkflow: CalculationWorkflow,
    private val decimalFormatter: DecimalFormatter
) : PluginBase(
    PluginDescription()
        .mainType(PluginType.GENERAL)
        .pluginName(R.string.iob_cob_calculator)
        .showInList(false)
        .neverVisible(true)
        .alwaysEnabled(true),
    aapsLogger, rh, injector
), IobCobCalculator {

    private val disposable = CompositeDisposable()

    private var iobTable = LongSparseArray<IobTotal>() // oldest at index 0
    private var basalDataTable = LongSparseArray<BasalData>() // oldest at index 0

    override var ads: AutosensDataStore = AutosensDataStoreObject()

    private val dataLock = Any()
    private var thread: Thread? = null

    override fun onStart() {
        super.onStart()
        // EventConfigBuilderChange
        disposable += rxBus
            .toObservable(EventConfigBuilderChange::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ event ->
                           resetDataAndRunCalculation("onEventConfigBuilderChange", event)
                       }, fabricPrivacy::logException)
        // EventEffectiveProfileSwitchChanged
        disposable += rxBus
            .toObservable(EventEffectiveProfileSwitchChanged::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ event ->
                           newHistoryData(event.startDate, false, event)
                       }, fabricPrivacy::logException)
        // EventPreferenceChange
        disposable += rxBus
            .toObservable(EventPreferenceChange::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ event ->
                           if (event.isChanged(rh.gs(app.aaps.core.utils.R.string.key_openapsama_autosens_period)) ||
                               event.isChanged(rh.gs(app.aaps.core.utils.R.string.key_age)) ||
                               event.isChanged(rh.gs(app.aaps.core.utils.R.string.key_absorption_maxtime)) ||
                               event.isChanged(rh.gs(app.aaps.core.utils.R.string.key_openapsama_min_5m_carbimpact)) ||
                               event.isChanged(rh.gs(app.aaps.core.utils.R.string.key_absorption_cutoff)) ||
                               event.isChanged(rh.gs(app.aaps.core.utils.R.string.key_openapsama_autosens_max)) ||
                               event.isChanged(rh.gs(app.aaps.core.utils.R.string.key_openapsama_autosens_min)) ||
                               event.isChanged(rh.gs(app.aaps.core.utils.R.string.key_insulin_oref_peak))
                           ) {
                               resetDataAndRunCalculation("onEventPreferenceChange", event)
                           }
                       }, fabricPrivacy::logException)
        // EventNewHistoryData
        disposable += rxBus
            .toObservable(EventNewHistoryData::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ event -> scheduleHistoryDataChange(event) }, fabricPrivacy::logException)
    }

    override fun onStop() {
        disposable.clear()
        super.onStop()
    }

    private fun resetDataAndRunCalculation(reason: String, event: Event?) {
        calculationWorkflow.stopCalculation(CalculationWorkflow.MAIN_CALCULATION, reason)
        clearCache()
        ads.reset()
        calculationWorkflow.runCalculation(
            job = CalculationWorkflow.MAIN_CALCULATION,
            iobCobCalculator = this,
            overviewData = overviewData,
            reason = reason,
            end = System.currentTimeMillis(),
            bgDataReload = true,
            cause = event
        )
    }

    override fun clearCache() {
        synchronized(dataLock) {
            aapsLogger.debug(LTag.AUTOSENS, "Clearing cached data.")
            iobTable = LongSparseArray()
            basalDataTable = LongSparseArray()
        }
    }

    private fun oldestDataAvailable(): Long {
        var oldestTime = System.currentTimeMillis()
        val oldestTempBasal = repository.getOldestTemporaryBasalRecord()
        if (oldestTempBasal != null) oldestTime = min(oldestTime, oldestTempBasal.timestamp)
        val oldestExtendedBolus = repository.getOldestExtendedBolusRecord()
        if (oldestExtendedBolus != null) oldestTime = min(oldestTime, oldestExtendedBolus.timestamp)
        val oldestBolus = repository.getOldestBolusRecord()
        if (oldestBolus != null) oldestTime = min(oldestTime, oldestBolus.timestamp)
        val oldestCarbs = repository.getOldestCarbsRecord()
        if (oldestCarbs != null) oldestTime = min(oldestTime, oldestCarbs.timestamp)
        val oldestPs = repository.getOldestEffectiveProfileSwitchRecord()
        if (oldestPs != null) oldestTime = min(oldestTime, oldestPs.timestamp)
        oldestTime -= 15 * 60 * 1000L // allow 15 min before
        return oldestTime
    }

    override fun calculateDetectionStart(from: Long, limitDataToOldestAvailable: Boolean): Long {
        val profile = profileFunction.getProfile(from)
        val dia = profile?.dia ?: Constants.defaultDIA
        val oldestDataAvailable = oldestDataAvailable()
        val getBGDataFrom: Long
        if (limitDataToOldestAvailable) {
            getBGDataFrom = max(oldestDataAvailable, (from - T.hours(1).msecs() * (24 + dia)).toLong())
            if (getBGDataFrom == oldestDataAvailable) aapsLogger.debug(LTag.AUTOSENS, "Limiting data to oldest available temps: " + dateUtil.dateAndTimeAndSecondsString(oldestDataAvailable))
        } else getBGDataFrom = (from - T.hours(1).msecs() * (24 + dia)).toLong()
        return getBGDataFrom
    }

    override fun calculateFromTreatmentsAndTemps(toTime: Long, profile: Profile): IobTotal {
        val now = System.currentTimeMillis()
        val time = ads.roundUpTime(toTime) // round up to minutes, presented in milliseconds
        val cacheHit = iobTable[time]

        if (time < now && cacheHit != null) {
            //log.debug(">>> calculateFromTreatmentsAndTemps Cache hit " + new Date(time).toLocaleString());
            return cacheHit
        } // else log.debug(">>> calculateFromTreatmentsAndTemps Cache miss " + new Date(time).toLocaleString());

        val bolusIob = calculateIobFromBolusToTime(time).round()
        val basalIob = calculateIobToTimeFromTempBasalsIncludingConvertedExtended(time).round()
        // OpenAPSSMB only
        // Add expected zero temp basal for next 240 minutes
        val basalIobWithZeroTemp = basalIob.copy()

        val temporaryBasal = TemporaryBasal(
            timestamp = now + 60 * 1000L,
            duration = 240 * 60 * 1000L,
            rate = 0.0,
            isAbsolute = true,
            type = TemporaryBasal.Type.NORMAL
        )

        if (temporaryBasal.timestamp < time) {
            val calc = temporaryBasal.iobCalc(time, profile, activePlugin.activeInsulin)
            basalIobWithZeroTemp.plus(calc)
        }

        basalIob.iobWithZeroTemp = IobTotal.combine(bolusIob, basalIobWithZeroTemp).round()
        val iobTotal = IobTotal.combine(bolusIob, basalIob).round()

        if (time < System.currentTimeMillis()) {
            synchronized(dataLock) {
                iobTable.put(time, iobTotal)
            }
        }

        return iobTotal
    }

    private fun calculateFromTreatmentsAndTemps(time: Long, lastAutosensResult: AutosensResult, exercise_mode: Boolean, half_basal_exercise_target: Int, isTempTarget: Boolean): IobTotal {
        val now = dateUtil.now()
        val bolusIob = calculateIobFromBolusToTime(time).round()
        val basalIob = getCalculationToTimeTempBasals(time, lastAutosensResult, exercise_mode, half_basal_exercise_target, isTempTarget).round()
        // OpenAPSSMB only
        // Add expected zero temp basal for next 240 minutes
        val basalIobWithZeroTemp = basalIob.copy()

        val temporaryBasal = TemporaryBasal(
            timestamp = now + 60 * 1000L,
            duration = 240 * 60 * 1000L,
            rate = 0.0,
            isAbsolute = true,
            type = TemporaryBasal.Type.NORMAL
        )

        if (temporaryBasal.timestamp < time) {
            val profile = profileFunction.getProfile(temporaryBasal.timestamp)
            if (profile != null) {
                val calc = temporaryBasal.iobCalc(time, profile, lastAutosensResult, exercise_mode, half_basal_exercise_target, isTempTarget, activePlugin.activeInsulin)
                basalIobWithZeroTemp.plus(calc)
            }
        }

        basalIob.iobWithZeroTemp = IobTotal.combine(bolusIob, basalIobWithZeroTemp).round()

        return IobTotal.combine(bolusIob, basalIob).round()
    }

    override fun getBasalData(profile: Profile, fromTime: Long): BasalData {
        val now = System.currentTimeMillis()
        val time = ads.roundUpTime(fromTime)
        var retVal = basalDataTable[time]
        if (retVal == null) {
            //log.debug(">>> getBasalData Cache miss " + new Date(time).toLocaleString());
            retVal = BasalData()
            val tb = getTempBasalIncludingConvertedExtended(time)
            retVal.basal = profile.getBasal(time)
            if (tb != null) {
                retVal.isTempBasalRunning = true
                retVal.tempBasalAbsolute = tb.convertedToAbsolute(time, profile)
            } else {
                retVal.isTempBasalRunning = false
                retVal.tempBasalAbsolute = retVal.basal
            }
            if (time < now) {
                synchronized(dataLock) {
                    basalDataTable.append(time, retVal)
                }
            }
        } //else log.debug(">>> getBasalData Cache hit " +  new Date(time).toLocaleString());
        return retVal
    }

    override fun getLastAutosensDataWithWaitForCalculationFinish(reason: String): AutosensData? {
        if (thread?.isAlive == true) {
            aapsLogger.debug(LTag.AUTOSENS, "AUTOSENSDATA is waiting for calculation thread: $reason")
            try {
                thread?.join(5000)
            } catch (ignored: InterruptedException) {
            }
            aapsLogger.debug(LTag.AUTOSENS, "AUTOSENSDATA finished waiting for calculation thread: $reason")
        }
        return ads.getLastAutosensData(reason, aapsLogger, dateUtil)
    }

    override fun getCobInfo(reason: String): CobInfo {
        val autosensData = ads.getLastAutosensData(reason, aapsLogger, dateUtil)
        var displayCob: Double? = null
        var futureCarbs = 0.0
        val now = dateUtil.now()
        var timestamp = now
        val carbs = repository.getCarbsDataFromTimeExpanded(autosensData?.time ?: now, true).blockingGet()
        if (autosensData != null) {
            displayCob = autosensData.cob
            carbs.forEach { carb ->
                if (carb.timestamp > autosensData.time && carb.timestamp <= now)
                    displayCob += carb.amount
            }
            timestamp = autosensData.time
        }
        // Future carbs
        carbs.forEach { carb -> if (carb.timestamp > now) futureCarbs += carb.amount }
        return CobInfo(timestamp, displayCob, futureCarbs)
    }

    override fun getFutureCob(): Double {
        var futureCarbs = 0.0
        val now = dateUtil.now()
        val carbs = repository.getCarbsDataFromTimeExpanded(now, true).blockingGet()
        carbs.forEach { carb -> if (carb.timestamp > now) futureCarbs += carb.amount }
        return futureCarbs
    }

    override fun getMostRecentCarbByDate(): Long? {
        return repository.getMostRecentCarbByDate()?.timestamp
    }

    override fun getMostRecentCarbAmount(): Double? {
        return repository.getMostRecentCarbByDate()?.amount
    }

    override fun getUserEntryDataWithNotesFromTime(timestamp: Long): List<UserEntry> {
        return repository.getUserEntryDataWithNotesFromTime(timestamp).blockingGet()
    }

    override fun getMealDataWithWaitingForCalculationFinish(): MealData {
        val result = MealData()
        val now = System.currentTimeMillis()
        val maxAbsorptionHours: Double = activePlugin.activeSensitivity.maxAbsorptionHours()
        val absorptionTimeAgo = now - (maxAbsorptionHours * T.hours(1).msecs()).toLong()
        repository.getCarbsDataFromTimeToTimeExpanded(absorptionTimeAgo + 1, now, true)
            .blockingGet()
            .forEach {
                if (it.amount > 0) {
                    result.carbs += it.amount
                    if (it.timestamp > result.lastCarbTime) result.lastCarbTime = it.timestamp
                }
            }
        val autosensData = getLastAutosensDataWithWaitForCalculationFinish("getMealData()")
        if (autosensData != null) {
            result.mealCOB = autosensData.cob
            result.slopeFromMinDeviation = autosensData.slopeFromMinDeviation
            result.slopeFromMaxDeviation = autosensData.slopeFromMaxDeviation
            result.usedMinCarbsImpact = autosensData.usedMinCarbsImpact
        }
        val lastBolus = repository.getLastBolusRecordWrapped().blockingGet()
        result.lastBolusTime = if (lastBolus is ValueWrapper.Existing) lastBolus.value.timestamp else 0L
        return result
    }

    override fun calculateIobArrayInDia(profile: Profile): Array<IobTotal> {
        // predict IOB (insulin on board) out to DIA (duration of insulin action) plus 30m
        var time = System.currentTimeMillis()
        time = ads.roundUpTime(time)

        // sargius, profile.dia задан в часах. Рассчитывается кол-во 5-минутных интервалов
        val len = ((profile.dia * 60 + 30) / 5).toInt()
        val array = Array(len) { IobTotal(0) }

        // TODO: (pos, i) не нужен, один из них можно убрать. Например, использовать только i
        for ((pos, i) in (0 until len).withIndex()) {
            val t = time + i * 5 * 60000
            val iob = calculateFromTreatmentsAndTemps(t, profile)
            array[pos] = iob
        }

        return array
    }

    override fun calculateIobArrayForSMB(lastAutosensResult: AutosensResult, exercise_mode: Boolean, half_basal_exercise_target: Int, isTempTarget: Boolean): Array<IobTotal> {
        // predict IOB out to DIA plus 30m
        val now = dateUtil.now()
        val len = 4 * 60 / 5
        val array = Array(len) { IobTotal(0) }
        for ((pos, i) in (0 until len).withIndex()) {
            val timeNextFiveMinutes = now + i * 5 * 60000
            val iob = calculateFromTreatmentsAndTemps(timeNextFiveMinutes, lastAutosensResult, exercise_mode, half_basal_exercise_target, isTempTarget)
            array[pos] = iob
        }
        return array
    }

    override fun iobArrayToString(array: Array<IobTotal>): String {
        val sb = StringBuilder()
        sb.append("[")
        for (i in array) {
            sb.append(decimalFormatter.to2Decimal(i.iob))
            sb.append(", ")
        }
        sb.append("]")
        return sb.toString()
    }

    // Limit rate of EventNewHistoryData
    private val historyWorker = Executors.newSingleThreadScheduledExecutor()
    private var scheduledHistoryPost: ScheduledFuture<*>? = null
    private var scheduledEvent: EventNewHistoryData? = null

    @Synchronized
    private fun scheduleHistoryDataChange(event: EventNewHistoryData) {
        // if there is nothing scheduled or asking reload deeper to the past
        if (scheduledEvent == null || event.oldDataTimestamp < (scheduledEvent?.oldDataTimestamp ?: 0L)) {
            // cancel waiting task to prevent sending multiple posts
            scheduledHistoryPost?.cancel(false)
            // prepare task for execution in 1 sec
            scheduledEvent?.let {
                // set reload bg data if was not set
                event.reloadBgData = event.reloadBgData || it.reloadBgData
            }
            scheduledEvent = event
            scheduledHistoryPost = historyWorker.schedule(
                {
                    synchronized(this) {
                        aapsLogger.debug(LTag.AUTOSENS, "Running newHistoryData")
                        repository.clearCachedTddData(MidnightTime.calc(event.oldDataTimestamp))
                        newHistoryData(
                            event.oldDataTimestamp,
                            event.reloadBgData,
                            if (event.newestGlucoseValueTimestamp != null) EventNewBG(event.newestGlucoseValueTimestamp) else event
                        )
                        scheduledEvent = null
                        scheduledHistoryPost = null
                    }
                }, 5L, TimeUnit.SECONDS
            )
        } else {
            // asked reload is newer -> adjust params only
            scheduledEvent?.let {
                // set reload bg data if was not set
                if (!it.reloadBgData) it.reloadBgData = event.reloadBgData
                // set Glucose value if newer
                event.newestGlucoseValueTimestamp?.let { timestamp ->
                    if (timestamp > (it.newestGlucoseValueTimestamp ?: 0L)) it.newestGlucoseValueTimestamp = timestamp
                }
            }
        }
    }

    // When historical data is changed (coming from NS etc) finished calculations after this date must be invalidated
    private fun newHistoryData(oldDataTimestamp: Long, bgDataReload: Boolean, event: Event) {
        //log.debug("Locking onNewHistoryData");
        calculationWorkflow.stopCalculation(CalculationWorkflow.MAIN_CALCULATION, "onEventNewHistoryData")
        synchronized(dataLock) {

            // clear up 5 min back for proper COB calculation
            val time = oldDataTimestamp - 5 * 60 * 1000L
            aapsLogger.debug(LTag.AUTOSENS, "Invalidating cached data to: " + dateUtil.dateAndTimeAndSecondsString(time))
            for (index in iobTable.size() - 1 downTo 0) {
                if (iobTable.keyAt(index) > time) {
                    aapsLogger.debug(LTag.AUTOSENS, "Removing from iobTable: " + dateUtil.dateAndTimeAndSecondsString(iobTable.keyAt(index)))
                    iobTable.removeAt(index)
                } else {
                    break
                }
            }
            for (index in basalDataTable.size() - 1 downTo 0) {
                if (basalDataTable.keyAt(index) > time) {
                    aapsLogger.debug(LTag.AUTOSENS, "Removing from basalDataTable: " + dateUtil.dateAndTimeAndSecondsString(basalDataTable.keyAt(index)))
                    basalDataTable.removeAt(index)
                } else {
                    break
                }
            }
            ads.newHistoryData(time, aapsLogger, dateUtil)
        }
        calculationWorkflow.runCalculation(
            job = CalculationWorkflow.MAIN_CALCULATION,
            iobCobCalculator = this,
            overviewData = overviewData,
            reason = event.javaClass.simpleName,
            end = System.currentTimeMillis(),
            bgDataReload = bgDataReload,
            cause = event
        )
        //log.debug("Releasing onNewHistoryData");
    }

    override fun convertToJSONArray(iobArray: Array<IobTotal>): JSONArray {
        val array = JSONArray()
        for (i in iobArray.indices) {
            array.put(iobArray[i].determineBasalJson(dateUtil))
        }
        return array
    }

    /**
     *  Time range to the past for IOB calculation
     *  @return milliseconds
     */
    fun range(): Long = ((/*overviewData.rangeToDisplay + */(profileFunction.getProfile()?.dia
        ?: Constants.defaultDIA)) * 60 * 60 * 1000).toLong()

    override fun calculateIobFromBolus(): IobTotal = calculateIobFromBolusToTime(dateUtil.now())

    /**
     * Calculate IobTotal from boluses and extended to provided timestamp.
     * NOTE: Only isValid == true boluses are included
     * NOTE: if faking by TBR by extended boluses is enabled, extended boluses are not included
     *  and are calculated towards temporary basals
     *
     * @param toTime timestamp in milliseconds
     * @return calculated iob
     */
    private fun calculateIobFromBolusToTime(toTime: Long): IobTotal {
        val total = IobTotal(toTime)
        val profile = profileFunction.getProfile() ?: return total
        val dia = profile.dia
        val divisor = sp.getDouble(app.aaps.core.utils.R.string.key_openapsama_bolus_snooze_dia_divisor, 2.0)
        assert(divisor > 0)

        val boluses = repository.getBolusesDataFromTime(toTime - range(), true).blockingGet()

        boluses.forEach { bolusAtTime ->
            if (bolusAtTime.isValid && bolusAtTime.timestamp < toTime) {
                val iobCalculatedFromBolus = bolusAtTime.iobCalc(activePlugin, toTime, dia)
                total.iob += iobCalculatedFromBolus.iobContrib
                total.activity += iobCalculatedFromBolus.activityContrib

                // reveal last bolus time of IobTotal for oref1 algo
                if (bolusAtTime.amount > 0 && bolusAtTime.timestamp > total.lastBolusTime) {
                    total.lastBolusTime = bolusAtTime.timestamp
                }

                if (bolusAtTime.type != Bolus.Type.SMB) {
                    // instead of dividing the DIA that only worked on the bilinear curves,
                    // multiply the time the treatment is seen active.
                    val timeSinceTreatment = toTime - bolusAtTime.timestamp
                    val snoozeTime = bolusAtTime.timestamp + (timeSinceTreatment * divisor).toLong()
                    val iobCalculatedForSnoozeTime = bolusAtTime.iobCalc(activePlugin, snoozeTime, dia)
                    total.bolussnooze += iobCalculatedForSnoozeTime.iobContrib
                }
            }
        }

        total.plus(calculateIobToTimeFromExtendedBoluses(toTime))
        return total
    }

    private fun calculateIobToTimeFromExtendedBoluses(toTime: Long): IobTotal {
        val total = IobTotal(toTime)
        val now = dateUtil.now()
        val pumpInterface = activePlugin.activePump

        if (!pumpInterface.isFakingTempsByExtendedBoluses) {
            val extendedBoluses = repository.getExtendedBolusDataFromTimeToTime(toTime - range(), toTime, true).blockingGet()

            for (pos in extendedBoluses.indices) {
                val extendedBolusAtPosition = extendedBoluses[pos]

                if (extendedBolusAtPosition.timestamp > toTime) {
                    continue
                }

                if (extendedBolusAtPosition.end > now) {
                    val newDuration = now - extendedBolusAtPosition.timestamp
                    extendedBolusAtPosition.amount *= newDuration.toDouble() / extendedBolusAtPosition.duration
                    extendedBolusAtPosition.duration = newDuration
                }

                val profile = profileFunction.getProfile(extendedBolusAtPosition.timestamp) ?: return total

                val iobCalculatedFromExtendedBolusAtPosition = extendedBolusAtPosition.iobCalc(toTime, profile, activePlugin.activeInsulin)
                total.plus(iobCalculatedFromExtendedBolusAtPosition)
            }
        }

        return total
    }

    override fun getTempBasal(timestamp: Long): TemporaryBasal? {
        val tb = repository.getTemporaryBasalActiveAt(timestamp).blockingGet()
        if (tb is ValueWrapper.Existing) return tb.value
        return null
    }

    override fun getExtendedBolus(timestamp: Long): ExtendedBolus? {
        val tb = repository.getExtendedBolusActiveAt(timestamp).blockingGet()
        if (tb is ValueWrapper.Existing) return tb.value
        return null
    }

    private fun getConvertedExtended(timestamp: Long): TemporaryBasal? {
        if (activePlugin.activePump.isFakingTempsByExtendedBoluses) {
            val eb = repository.getExtendedBolusActiveAt(timestamp).blockingGet()
            val profile = profileFunction.getProfile(timestamp) ?: return null
            if (eb is ValueWrapper.Existing) return eb.value.toTemporaryBasal(profile)
        }
        return null
    }

    override fun getTempBasalIncludingConvertedExtended(timestamp: Long): TemporaryBasal? {
        val tb = repository.getTemporaryBasalActiveAt(timestamp).blockingGet()
        if (tb is ValueWrapper.Existing) return tb.value
        return getConvertedExtended(timestamp)
    }

    override fun getTempBasalIncludingConvertedExtendedForRange(startTime: Long, endTime: Long, calculationStep: Long): Map<Long, TemporaryBasal?> {
        val tempBasals = HashMap<Long, TemporaryBasal?>()
        val tbs = repository.getTemporaryBasalsDataActiveBetweenTimeAndTime(startTime, endTime).blockingGet()
        for (t in startTime until endTime step calculationStep) {
            val tb = tbs.firstOrNull { basal -> basal.timestamp <= t && (basal.timestamp + basal.duration) > t }
            tempBasals[t] = tb ?: getConvertedExtended(t)
        }
        return tempBasals
    }

    override fun calculateAbsoluteIobFromBaseBasals(toTime: Long): IobTotal {
        val total = IobTotal(toTime)
        var i = toTime - range()
        while (i < toTime) {
            val profile = profileFunction.getProfile(i)
            if (profile == null) {
                i += T.mins(5).msecs()
                continue
            }
            val running = profile.getBasal(i)
            val bolus = Bolus(
                timestamp = i,
                amount = running * 5.0 / 60.0,
                type = Bolus.Type.NORMAL,
                isBasalInsulin = true
            )
            val iob = bolus.iobCalc(activePlugin, toTime, profile.dia)
            total.basaliob += iob.iobContrib
            total.activity += iob.activityContrib
            i += T.mins(5).msecs()
        }
        return total
    }

    override fun calculateIobFromTempBasalsIncludingConvertedExtended(): IobTotal =
        calculateIobToTimeFromTempBasalsIncludingConvertedExtended(dateUtil.now())

    override fun calculateIobToTimeFromTempBasalsIncludingConvertedExtended(toTime: Long): IobTotal {
        val total = IobTotal(toTime)
        val now = dateUtil.now()
        val pumpInterface = activePlugin.activePump

        val temporaryBasals = repository.getTemporaryBasalsDataFromTimeToTime(toTime - range(), toTime, true).blockingGet()

        for (pos in temporaryBasals.indices) {
            val temporaryBasalAtTime = temporaryBasals[pos]

            if (temporaryBasalAtTime.timestamp > toTime) continue
            val profile = profileFunction.getProfile(temporaryBasalAtTime.timestamp) ?: continue

            if (temporaryBasalAtTime.end > now) {
                temporaryBasalAtTime.duration = now - temporaryBasalAtTime.timestamp
            }

            val calc = temporaryBasalAtTime.iobCalc(toTime, profile, activePlugin.activeInsulin)
            //log.debug("BasalIOB " + new Date(time) + " >>> " + calc.basalIob);
            total.plus(calc)
        }

        if (pumpInterface.isFakingTempsByExtendedBoluses) {
            val totalExt = IobTotal(toTime)
            val extendedBoluses = repository.getExtendedBolusDataFromTimeToTime(toTime - range(), toTime, true).blockingGet()

            for (pos in extendedBoluses.indices) {
                val extendedBolusAtTime = extendedBoluses[pos]

                if (extendedBolusAtTime.timestamp > toTime) continue
                val profile = profileFunction.getProfile(extendedBolusAtTime.timestamp) ?: continue

                if (extendedBolusAtTime.end > now) {
                    val newDuration = now - extendedBolusAtTime.timestamp
                    extendedBolusAtTime.amount *= newDuration.toDouble() / extendedBolusAtTime.duration
                    extendedBolusAtTime.duration = newDuration
                }

                val calc = extendedBolusAtTime.iobCalc(toTime, profile, activePlugin.activeInsulin)
                totalExt.plus(calc)
            }

            // Convert to basal iob
            totalExt.basaliob = totalExt.iob
            totalExt.iob = 0.0
            totalExt.netbasalinsulin = totalExt.extendedBolusInsulin
            totalExt.hightempinsulin = totalExt.extendedBolusInsulin
            total.plus(totalExt)
        }

        return total
    }

    private fun getCalculationToTimeTempBasals(toTime: Long, lastAutosensResult: AutosensResult, exercise_mode: Boolean, half_basal_exercise_target: Int, isTempTarget: Boolean): IobTotal {
        val total = IobTotal(toTime)
        val pumpInterface = activePlugin.activePump
        val now = dateUtil.now()
        val temporaryBasals = repository.getTemporaryBasalsDataFromTimeToTime(toTime - range(), toTime, true).blockingGet()

        for (pos in temporaryBasals.indices) {
            val temporaryBasalAtTime = temporaryBasals[pos]

            if (temporaryBasalAtTime.timestamp > toTime) continue
            val profile = profileFunction.getProfile(temporaryBasalAtTime.timestamp) ?: continue

            if (temporaryBasalAtTime.end > now) {
                temporaryBasalAtTime.duration = now - temporaryBasalAtTime.timestamp
            }

            val calc = temporaryBasalAtTime.iobCalc(toTime, profile, lastAutosensResult, exercise_mode, half_basal_exercise_target, isTempTarget, activePlugin.activeInsulin)
            //log.debug("BasalIOB " + new Date(time) + " >>> " + calc.basalIob);
            total.plus(calc)
        }

        if (pumpInterface.isFakingTempsByExtendedBoluses) {
            val totalExt = IobTotal(toTime)
            val extendedBoluses = repository.getExtendedBolusDataFromTimeToTime(toTime - range(), toTime, true).blockingGet()

            for (pos in extendedBoluses.indices) {
                val extendedBolusAtTime = extendedBoluses[pos]

                if (extendedBolusAtTime.timestamp > toTime) continue
                val profile = profileFunction.getProfile(extendedBolusAtTime.timestamp) ?: continue

                if (extendedBolusAtTime.end > now) {
                    val newDuration = now - extendedBolusAtTime.timestamp
                    extendedBolusAtTime.amount *= newDuration.toDouble() / extendedBolusAtTime.duration
                    extendedBolusAtTime.duration = newDuration
                }

                val calc = extendedBolusAtTime.iobCalc(toTime, profile, lastAutosensResult, exercise_mode, half_basal_exercise_target, isTempTarget, activePlugin.activeInsulin)
                totalExt.plus(calc)
            }

            // Convert to basal iob
            totalExt.basaliob = totalExt.iob
            totalExt.iob = 0.0
            totalExt.netbasalinsulin = totalExt.extendedBolusInsulin
            totalExt.hightempinsulin = totalExt.extendedBolusInsulin
            total.plus(totalExt)
        }

        return total
    }
}