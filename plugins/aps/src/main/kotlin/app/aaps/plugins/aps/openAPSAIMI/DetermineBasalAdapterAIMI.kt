package app.aaps.plugins.aps.openAPSAIMI

import android.os.Environment
import app.aaps.core.interfaces.aps.DetermineBasalAdapter
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.GlucoseUnit
import app.aaps.core.interfaces.iob.GlucoseStatus
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.iob.IobTotal
import app.aaps.core.interfaces.iob.MealData
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.Round
import app.aaps.core.interfaces.utils.SafeParse
import app.aaps.core.main.extensions.convertedToAbsolute
import app.aaps.core.main.extensions.getPassedDurationToTimeInMinutes
import app.aaps.core.main.extensions.plannedRemainingMinutes
import app.aaps.database.ValueWrapper
import app.aaps.database.entities.Bolus
import app.aaps.database.entities.UserEntry
import app.aaps.database.impl.AppRepository
import app.aaps.plugins.aps.APSResultObject
import app.aaps.plugins.aps.R
import dagger.android.HasAndroidInjector
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import kotlin.math.roundToInt
import java.util.Calendar
import kotlin.math.min
import org.tensorflow.lite.Interpreter
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.LocalTime
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.round

class DetermineBasalAdapterAIMI internal constructor(private val injector: HasAndroidInjector) : DetermineBasalAdapter {

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var constraintChecker: ConstraintsChecker
    @Inject lateinit var sp: SP
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var iobCobCalculator: IobCobCalculator
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var repository: AppRepository
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var tddCalculator: TddCalculator

    private var iob = 0.0f
    private var cob = 0.0f
    private var lastCarbAgeMin: Int = 0
    private var futureCarbs = 0.0f
    private var recentNotes: List<UserEntry>? = null
    private var tags0to60minAgo = ""
    private var tags60to120minAgo = ""
    private var tags120to180minAgo = ""
    private var tags180to240minAgo = ""
    private var bg = 0.0f
    private var targetBg = 100.0f
    private var normalBgThreshold = 150.0f
    private var predictedBg = 0.0f
    private var delta = 0.0f
    private var shortAvgDelta = 0.0f
    private var longAvgDelta = 0.0f
    private var lastsmbtime = 0
    private var acceleratingUp: Int = 0
    private var decceleratingUp: Int = 0
    private var acceleratingDown: Int = 0
    private var decceleratingDown: Int = 0
    private var stable: Int = 0
    private var maxIob = 0.0f
    private var maxSMB = 1.0f
    private var tdd7DaysPerHour = 0.0f
    private var tdd2DaysPerHour = 0.0f
    private var tddPerHour = 0.0f
    private var tdd24HrsPerHour = 0.0f
    private var hourOfDay: Int = 0
    private var weekend: Int = 0
    private var recentSteps5Minutes: Int = 0
    private var recentSteps10Minutes: Int = 0
    private var recentSteps15Minutes: Int = 0
    private var recentSteps30Minutes: Int = 0
    private var recentSteps60Minutes: Int = 0
    private var recentSteps180Minutes: Int = 0
    private var basalaimi = 0.0f
    private var basalSMB = 0.0f
    private var aimilimit = 0.0f
    private var basaloapsaimirate = 0.0f
    private var CI = 0.0f
    private var variableSensitivity = 0.0f
    private var averageBeatsPerMinute = 0.0
    private var averageBeatsPerMinute180 = 0.0
    private var b30upperbg = 0.0
    private var b30upperdelta = 0.0
    private var profile = JSONObject()
    private var glucoseStatus = JSONObject()
    private var iobData: JSONArray? = null
    private var mealData = JSONObject()
    private var currentTemp = JSONObject()
    private var autosensData = JSONObject()
    private val path = File(Environment.getExternalStorageDirectory().toString())
    private val modelFile = File(path, "AAPS/ml/model.tflite")
    private val modelHBFile = File(path, "AAPS/ml/modelHB.tflite")

    override var currentTempParam: String? = null
    override var iobDataParam: String? = null
    override var glucoseStatusParam: String? = null
    override var profileParam: String? = null
    override var mealDataParam: String? = null
    override var scriptDebug = ""

    private var now: Long = 0


    @Suppress("SpellCheckingInspection")
    override operator fun invoke(): APSResultObject {
        aapsLogger.debug(LTag.APS, ">>> Invoking determine_basal <<<")

        val predictedSMB = calculateSMBFromModel()
        var smbToGive = predictedSMB
        val morningfactor = SafeParse.stringToDouble(sp.getString(R.string.key_oaps_aimi_morning_factor, "50")) / 100.0
        val afternoonfactor = SafeParse.stringToDouble(sp.getString(R.string.key_oaps_aimi_afternoon_factor, "50")) / 100.0
        val eveningfactor = SafeParse.stringToDouble(sp.getString(R.string.key_oaps_aimi_evening_factor, "50")) / 100.0
        val hyperfactor = SafeParse.stringToDouble(sp.getString(R.string.key_oaps_aimi_hyper_factor, "30")) / 100.0

        smbToGive = when {
            hourOfDay in 1..11 -> smbToGive * morningfactor.toFloat()
            hourOfDay in 12..18 -> smbToGive * afternoonfactor.toFloat()
            hourOfDay in 19..23 -> smbToGive * eveningfactor.toFloat()
            bg > 180 -> (smbToGive * hyperfactor).toFloat()
            else -> smbToGive
        }
        smbToGive = applySafetyPrecautions(smbToGive)
        smbToGive = roundToPoint05(smbToGive)

        logDataToCsv(predictedSMB, smbToGive)
        logDataToCsvHB(predictedSMB,smbToGive)

        val constraintStr = " Max IOB: $maxIob <br/> Max SMB: $maxSMB"

        val glucoseStr = " bg: $bg <br/> targetBg: $targetBg <br/> futureBg: $predictedBg <br/>" +
            "delta: $delta <br/> short avg delta: $shortAvgDelta <br/> long avg delta: $longAvgDelta <br/>" +
            " accelerating_up: $acceleratingUp <br/> deccelerating_up: $decceleratingUp <br/> accelerating_down: $acceleratingDown <br/> deccelerating_down: $decceleratingDown <br/> stable: $stable"

        val iobStr = " IOB: $iob <br/> tdd 7d/h: ${roundToPoint05(tdd7DaysPerHour)} <br/> " +
            "tdd 2d/h : ${roundToPoint05(tdd2DaysPerHour)} <br/> " +
            "tdd daily/h : ${roundToPoint05(tddPerHour)} <br/> " +
            "tdd 24h/h : ${roundToPoint05(tdd24HrsPerHour)}"

        val profileStr = " Hour of day: $hourOfDay <br/> Weekend: $weekend <br/>" +
            " 5 Min Steps: $recentSteps5Minutes <br/> 10 Min Steps: $recentSteps10Minutes <br/> 15 Min Steps: $recentSteps15Minutes <br/>" +
            " 30 Min Steps: $recentSteps30Minutes <br/> 60 Min Steps: $recentSteps60Minutes <br/> 180 Min Steps: $recentSteps180Minutes <br/>" +
            "ISF : $variableSensitivity <br/> Heart Beat per minute(average past 5 minutes) : $averageBeatsPerMinute <br/> Heart Beat per minute(average past 180 minutes) : $averageBeatsPerMinute180"

        var mealStr = " COB: ${cob}g   Future: ${futureCarbs}g <br/> COB Age Min: $lastCarbAgeMin <br/><br/> "
        mealStr += "tags0to60minAgo: ${tags0to60minAgo}<br/> tags60to120minAgo: $tags60to120minAgo<br/> " +
            "tags120to180minAgo: $tags120to180minAgo<br/> tags180to240minAgo: $tags180to240minAgo"

        val reason = "The ai model predicted SMB of ${roundToPoint001(predictedSMB)}u and after safety requirements and rounding to .05, requested ${smbToGive}u to the pump" +
         ",<br/> Version du plugin OpenApsAIMI.1 ML.2, 12 novembre 2023"

        val determineBasalResultAIMISMB = DetermineBasalResultAIMISMB(injector, smbToGive, constraintStr, glucoseStr, iobStr, profileStr, mealStr, reason)

        glucoseStatusParam = glucoseStatus.toString()
        iobDataParam = iobData.toString()
        currentTempParam = currentTemp.toString()
        profileParam = profile.toString()
        mealDataParam = mealData.toString()

        return determineBasalResultAIMISMB
    }

    private fun logDataToCsv(predictedSMB: Float, smbToGive: Float) {
        val dateStr = dateUtil.dateAndTimeString(dateUtil.now())

        val headerRow = "dateStr,dateLong,hourOfDay,weekend," +
            "bg,targetBg,iob,cob,lastCarbAgeMin,futureCarbs,delta,shortAvgDelta,longAvgDelta," +
            "tdd7DaysPerHour,tdd2DaysPerHour,tddPerHour,tdd24HrsPerHour," +
            "recentSteps5Minutes,recentSteps10Minutes,recentSteps15Minutes,recentSteps30Minutes,recentSteps60Minutes,recentSteps180Minutes" +
            "tags0to60minAgo,tags60to120minAgo,tags120to180minAgo,tags180to240minAgo," +
            "predictedSMB,maxIob,maxSMB,smbGiven\n"

        val valuesToRecord = "$dateStr,${dateUtil.now()},$hourOfDay,$weekend," +
            "$bg,$targetBg,$iob,$cob,$lastCarbAgeMin,$futureCarbs,$delta,$shortAvgDelta,$longAvgDelta," +
            "$tdd7DaysPerHour,$tdd2DaysPerHour,$tddPerHour,$tdd24HrsPerHour," +
            "$recentSteps5Minutes,$recentSteps10Minutes,$recentSteps15Minutes,$recentSteps30Minutes,$recentSteps60Minutes,$recentSteps180Minutes" +
            "$tags0to60minAgo,$tags60to120minAgo,$tags120to180minAgo,$tags180to240minAgo," +
            "$predictedSMB,$maxIob,$maxSMB,$smbToGive"

        val file = File(path, "AAPS/aimiSMB_records.csv")
        if (!file.exists()) {
            file.createNewFile()
            file.appendText(headerRow)
        }
        file.appendText(valuesToRecord + "\n")
    }

    private fun logDataToCsvHB(predictedSMB: Float, smbToGive: Float) {
        val dateStr = dateUtil.dateAndTimeString(dateUtil.now())

        val headerRow = "dateStr,dateLong,hourOfDay,weekend," +
            "bg,targetBg,iob,cob,lastCarbAgeMin,futureCarbs,delta,shortAvgDelta,longAvgDelta," +
            "accelerating_up,deccelerating_up,accelerating_down,deccelerating_down,stable," +
            "tdd7DaysPerHour,tdd2DaysPerHour,tddDailyPerHour,tdd24HrsPerHour," +
            "recentSteps5Minutes,recentSteps10Minutes,recentSteps15Minutes,recentSteps30Minutes,recentSteps60Minutes,averageBeatsPerMinute," +
            "tags0to60minAgo,tags60to120minAgo,tags120to180minAgo,tags180to240minAgo," +
            "variableSensitivity,lastbolusage,predictedSMB,maxIob,maxSMB,smbGiven\n"

        val valuesToRecord = "$dateStr,${dateUtil.now()},$hourOfDay,$weekend," +
            "$bg,$targetBg,$iob,$cob,$lastCarbAgeMin,$futureCarbs,$delta,$shortAvgDelta,$longAvgDelta," +
            "$acceleratingUp,$decceleratingUp,$acceleratingDown,$decceleratingDown,$stable," +
            "$tdd7DaysPerHour,$tdd2DaysPerHour,$tddPerHour,$tdd24HrsPerHour," +
            "$recentSteps5Minutes,$recentSteps10Minutes,$recentSteps15Minutes,$recentSteps30Minutes,$recentSteps60Minutes,$recentSteps180Minutes," +
            "$averageBeatsPerMinute, $averageBeatsPerMinute180" +
            "$tags0to60minAgo,$tags60to120minAgo,$tags120to180minAgo,$tags180to240minAgo," +
            "$variableSensitivity,$predictedSMB,$maxIob,$maxSMB,$smbToGive"

        val file = File(path, "AAPS/aiSMB_NewrecordsHBeat.csv")
        if (!file.exists()) {
            file.createNewFile()
            file.appendText(headerRow)
        }
        file.appendText(valuesToRecord + "\n")
    }

    private fun applySafetyPrecautions(smbToGiveParam: Float): Float {
        var smbToGive = smbToGiveParam

        // Vérifier les conditions de sécurité critiques
        if (isCriticalSafetyCondition()) {
            return 0.0f  // Arrêt immédiat si une condition de sécurité critique est remplie
        }

        // Ajustements basés sur des conditions spécifiques
        smbToGive = applySpecificAdjustments(smbToGive)
        // Assurer que smbToGive n'est pas négatif et appliquer des ajustements finaux
        smbToGive = finalizeSmbToGive(smbToGive)
        // Appliquer les limites maximum
        smbToGive = applyMaxLimits(smbToGive)

        return smbToGive
    }

    private fun applyMaxLimits(smbToGive: Float): Float {
        var result = smbToGive

        // Vérifiez d'abord si smbToGive dépasse maxSMB
        if (result > maxSMB) {
            result = maxSMB
        }

        // Ensuite, vérifiez si la somme de iob et smbToGive dépasse maxIob
        if (iob + result > maxIob) {
            result = maxIob - iob
        }

        return result
    }

    private fun isCriticalSafetyCondition(): Boolean {
        val belowMinThreshold = bg < 80
        val belowTargetAndDropping = bg < targetBg && delta < -2
        val belowTargetAndStableButNoCob = bg < targetBg - 15 && shortAvgDelta <= 2 && cob <= 5
        val droppingFast = bg < 150 && delta < -5
        val droppingFastAtHigh = bg < 200 && delta < -7
        val droppingVeryFast = delta < -10
        val prediction = predictedBg < targetBg && delta < 10

        return belowMinThreshold || belowTargetAndDropping || belowTargetAndStableButNoCob ||
            droppingFast || droppingFastAtHigh || droppingVeryFast || prediction
    }

    private fun applySpecificAdjustments(smb: Float): Float {
        var result = smb

        // Vos autres conditions et ajustements moins critiques viennent ici
        if (delta < b30upperdelta && delta > 2 && bg < b30upperbg && lastsmbtime < 15) {
            result = 0.0f
        } else if (delta < b30upperdelta && delta > 2 && bg < b30upperbg && lastsmbtime > 15) {
            result = basalSMB
        }

        val safetySmb = recentSteps180Minutes > 1500 && bg < 130
        if (safetySmb) {
            result /= 2
        }

        if (recentSteps5Minutes > 100 && recentSteps30Minutes > 500 && lastsmbtime < 20) {
            result = 0.0f
        }

        return result
    }

    private fun finalizeSmbToGive(smb: Float): Float {
        var result = smb
        // Assurez-vous que smbToGive n'est pas négatif
        if (result < 0.0f) {
            result = 0.0f
        }

        // Logique finale pour ajuster smbToGive
        if (result == 0.0f && delta > 2 && bg > 100 && lastsmbtime > 15) {
            result = if ((iob + basalSMB) > maxIob) maxIob - iob else basalSMB
        }

        return result
    }

    private fun roundToPoint05(number: Float): Float {
        return (number * 20.0).roundToInt() / 20.0f
    }

    private fun roundToPoint001(number: Float): Float {
        return (number * 1000.0).roundToInt() / 1000.0f
    }

    private fun calculateSMBFromModel(): Float {
        val selectedModelFile: File?
        val modelInputs: FloatArray

        when {
            modelHBFile.exists() -> {
                selectedModelFile = modelHBFile
                modelInputs = floatArrayOf(
                    hourOfDay.toFloat(), weekend.toFloat(),
                    bg, targetBg, iob, delta, shortAvgDelta, longAvgDelta,
                    tdd7DaysPerHour, tdd2DaysPerHour, tddPerHour, tdd24HrsPerHour, averageBeatsPerMinute.toFloat()
                )
            }

            modelFile.exists() -> {
                selectedModelFile = modelFile
                modelInputs = floatArrayOf(
                    hourOfDay.toFloat(), weekend.toFloat(),
                    bg, targetBg, iob, delta, shortAvgDelta, longAvgDelta,
                    tdd7DaysPerHour, tdd2DaysPerHour, tddPerHour, tdd24HrsPerHour
                )
            }

            else -> {
                aapsLogger.error(LTag.APS, "NO Model found at specified location")
                return 0.0F
            }
        }

        val interpreter = Interpreter(selectedModelFile)
        val output = arrayOf(floatArrayOf(0.0F))

        interpreter.run(modelInputs, output)
        interpreter.close()

        var smbToGive = output[0][0].toString().replace(',', '.').toDouble()

        val formatter = DecimalFormat("#.####", DecimalFormatSymbols(Locale.US))

        smbToGive = formatter.format(smbToGive).toDouble()

        return smbToGive.toFloat()
    }

    private fun calculateAdjustedDelayFactor(bg: Float, recentSteps180Minutes: Int): Float {
        // Seuil pour une activité physique significative
        // Порог значимой физической активности
        val stepActivityThreshold = 1500

        // Seuil à partir duquel l'efficacité de l'insuline commence à diminuer
        // Порог, при котором эффективность инсулина начинает снижаться
        val insulinSensitivityDecreaseThreshold = 1.5 * normalBgThreshold

        // Déterminer si une activité physique significative a eu lieu
        // Определите, произошла ли значительная физическая активность
        val increasedPhysicalActivity = recentSteps180Minutes > stepActivityThreshold

        // Calculer le facteur de base avant de prendre en compte l'activité physique
        // Рассчитайте базовый коэффициент, прежде чем учитывать физическую активность.
        val baseFactor = when {
            bg <= normalBgThreshold -> 1f
            bg <= insulinSensitivityDecreaseThreshold -> 1f - ((bg - normalBgThreshold) / (insulinSensitivityDecreaseThreshold - normalBgThreshold))
            else -> 0.5f // Arbitraire, à ajuster en fonction de la physiologie individuelle
                         // Произвольный, регулируется в соответствии с индивидуальной физиологией.
        }

        // Si une activité physique est détectée (soit par les étapes, soit par la fréquence cardiaque),
        // nous ajustons le facteur de retard pour augmenter la sensibilité à l'insuline.
        // Если обнаружена физическая активность (по шагам или по частоте пульса),
        // мы корректируем коэффициент задержки, чтобы повысить чувствительность к инсулину.
        return if (increasedPhysicalActivity) {
            // Ici, nous utilisons 0.8f pour indiquer qu'il s'agit d'un Float
            // Здесь мы используем 0,8f, чтобы указать, что это число с плавающей запятой.
            (baseFactor.toFloat() * 0.8f).coerceAtLeast(0.5f)

        } else {
            // Cela devrait déjà être un Float
            // Это уже должно быть Float
            baseFactor.toFloat()
        }
    }

    private fun calculateInsulinEffect(
        bg: Float,
        iob: Float,
        variableSensitivity: Float,
        cob: Float,
        normalBgThreshold: Float,
        recentSteps180Min: Int
    ): Float {
        // Calculer l'effet initial de l'insuline
        // Рассчитать первоначальный эффект инсулина
        var insulinEffect = iob * variableSensitivity

        // Si des glucides sont présents, nous pourrions vouloir ajuster l'effet de l'insuline pour tenir compte de l'absorption des glucides.
        // Если присутствуют углеводы, мы можем захотеть скорректировать действие инсулина с учетом поглощения углеводов.
        if (cob > 0) {
            // Ajustement hypothétique basé sur la présence de glucides. Ce facteur doit être déterminé par des tests/logique métier.
            // Гипотетическая корректировка, основанная на наличии углеводов. Этот фактор должен определяться путем тестирования/бизнес-логики.
            insulinEffect *= 0.9f
        }

        // Calculer le facteur de retard ajusté en fonction de l'activité physique
        // Рассчитайте коэффициент задержки с поправкой на физическую активность.
        val adjustedDelayFactor = calculateAdjustedDelayFactor(
            normalBgThreshold,
            recentSteps180Min
        )

        // Appliquer le facteur de retard ajusté à l'effet de l'insuline
        // Примените коэффициент задержки, скорректированный с учетом эффекта инсулина.
        insulinEffect *= adjustedDelayFactor

        // En fonction de la glycémie actuelle, nous pourrions vouloir faire d'autres ajustements.
        // В зависимости от текущего уровня сахара в крови мы можем внести дополнительные корректировки.
        if (bg > normalBgThreshold) {
            // Si la glycémie est élevée, l'effet de l'insuline peut être différent. Ajustez selon la logique métier/test.
            // Если уровень сахара в крови повышен, действие инсулина может быть иным. Отрегулируйте в соответствии с бизнес-логикой/логикой тестирования.
            insulinEffect *= 1.1f
        }

        return insulinEffect
    }

    private fun predictFutureBg(
        bg: Float,
        iob: Float,  // Insuline active (IOB)
        variableSensitivity: Float,  // Facteur de sensibilité à l'insuline (ISF)
        cob: Float,  // Glucides à bord (COB)
        CI: Float,  // Rapport insuline/glucides (ICR) // Соотношение инсулин/углеводы (ICR)
    ): Float {
        // Temps moyen d'absorption des glucides en heures // Среднее время усвоения углеводов в часах
        val averageCarbAbsorptionTime = 2.5f

        // Convertir le temps d'absorption en minutes pour le calcul
        // Преобразуйте время поглощения в минуты для расчета.
        val absorptionTimeInMinutes = averageCarbAbsorptionTime * 60

        // Calculer l'effet de l'insuline sur la baisse de la glycémie
        // Рассчитайте влияние инсулина на снижение уровня сахара в крови.
        val insulinEffect = calculateInsulinEffect(
            bg,
            iob,
            variableSensitivity,
            cob,
            normalBgThreshold,
            recentSteps180Minutes
        )

        // Calculer l'effet des glucides sur l'augmentation de la glycémie
        // en supposant que 'absorptionTime' représente la période de temps pendant laquelle les glucides sont absorbés
        // Рассчитайте влияние углеводов на повышение уровня сахара в крови, предполагая,
        // что «Время поглощения» представляет собой период времени, в течение которого углеводы всасываются.
        val carbEffect = (cob / absorptionTimeInMinutes) * CI

        // Prédire la glycémie future // Прогнозирование будущего уровня сахара в крови
        val futureBg = bg - insulinEffect + carbEffect

        return futureBg
    }

    @Suppress("SpellCheckingInspection")
    @Throws(JSONException::class)
    override fun setData(
        profile: Profile,
        maxIob: Double,
        maxBasal: Double,
        minBg: Double,
        maxBg: Double,
        targetBg: Double,
        basalRate: Double,
        iobArray: Array<IobTotal>,
        glucoseStatus: GlucoseStatus,
        mealData: MealData,
        autosensDataRatio: Double,
        tempTargetSet: Boolean,
        microBolusAllowed: Boolean,
        uamAllowed: Boolean,
        advancedFiltering: Boolean,
        flatBGsDetected: Boolean,
        tdd1D: Double?,
        tdd7D: Double?,
        tddLast24H: Double?,
        tddLast4H: Double?,
        tddLast8to4H: Double?
    ) {
        this.now = System.currentTimeMillis()
        val calendarInstance = Calendar.getInstance()
        this.hourOfDay = calendarInstance[Calendar.HOUR_OF_DAY]
        val dayOfWeek = calendarInstance[Calendar.DAY_OF_WEEK]
        this.weekend = if (dayOfWeek == Calendar.SUNDAY || dayOfWeek == Calendar.SATURDAY) 1 else 0

        val iobCalcs = iobCobCalculator.calculateIobFromBolus()
        this.iob = iobCalcs.iob.toFloat() + iobCalcs.basaliob.toFloat()
        this.bg = glucoseStatus.glucose.toFloat()
        this.targetBg = targetBg.toFloat()
        this.cob = mealData.mealCOB.toFloat()

        var lastCarbTimestamp = mealData.lastCarbTime

        if (lastCarbTimestamp.toInt() == 0) {
            val oneDayAgoIfNotFound = now - 24 * 60 * 60 * 1000
            lastCarbTimestamp = iobCobCalculator.getMostRecentCarbByDate() ?: oneDayAgoIfNotFound
        }

        this.lastCarbAgeMin = ((now - lastCarbTimestamp) / (60 * 1000)).toDouble().roundToInt()

        if (lastCarbAgeMin < 15 && cob == 0.0f) {
            this.cob = iobCobCalculator.getMostRecentCarbAmount()?.toFloat() ?: 0.0f
        }

        this.futureCarbs = iobCobCalculator.getFutureCob().toFloat()
        val fourHoursAgo = now - 4 * 60 * 60 * 1000
        this.recentNotes = iobCobCalculator.getUserEntryDataWithNotesFromTime(fourHoursAgo)
        this.tags0to60minAgo = parseNotes(0, 60)
        this.tags60to120minAgo = parseNotes(60, 120)
        this.tags120to180minAgo = parseNotes(120, 180)
        this.tags180to240minAgo = parseNotes(180, 240)
        this.delta = glucoseStatus.delta.toFloat()
        this.shortAvgDelta = glucoseStatus.shortAvgDelta.toFloat()
        this.longAvgDelta = glucoseStatus.longAvgDelta.toFloat()

        var nowMinutes = calendarInstance[Calendar.HOUR_OF_DAY] + calendarInstance[Calendar.MINUTE] / 60.0 + calendarInstance[Calendar.SECOND] / 3600.0
        nowMinutes = round(nowMinutes * 100) / 100  // Arrondi à 2 décimales

        val circadianSensitivity = (0.00000379 * nowMinutes.pow(5)) -
            (0.00016422 * nowMinutes.pow(4)) +
            (0.00128081 * nowMinutes.pow(3)) +
            (0.02533782 * nowMinutes.pow(2)) -
            (0.33275556 * nowMinutes) +
            1.38581503

        val circadianSmb = round(
            ((0.00000379 * delta * nowMinutes.pow(5)) -
                (0.00016422 * delta * nowMinutes.pow(4)) +
                (0.00128081 * delta * nowMinutes.pow(3)) +
                (0.02533782 * delta * nowMinutes.pow(2)) -
                (0.33275556 * delta * nowMinutes) +
                1.38581503) * 100
        ) / 100  // Arrondi à 2 décimales

        this.targetBg = when {
            bg >= 110 && delta > 5 -> {
                var hyperTarget = kotlin.math.max(72.0, profile.getTargetLowMgdl() - (bg - profile.getTargetLowMgdl()) / 3).roundToInt()
                hyperTarget = (hyperTarget * kotlin.math.min(circadianSensitivity, 1.0)).toInt()
                kotlin.math.max(hyperTarget, 72).toFloat()
            }

            circadianSmb > 0.1 && bg < 110 -> {
                val hypoTarget = 100 * kotlin.math.max(1.0, circadianSensitivity)
                (hypoTarget + circadianSmb).toFloat()
            }

            recentSteps5Minutes >= 0 && recentSteps30Minutes >= 500 || recentSteps180Minutes > 1500 && recentSteps5Minutes > 0 -> 120.0f
            else -> this.targetBg // or any default value you want to assign
        }

        this.acceleratingUp = if (delta > 2 && delta - longAvgDelta > 2) 1 else 0
        this.decceleratingUp = if (delta > 0 && (delta < shortAvgDelta || delta < longAvgDelta)) 1 else 0
        this.acceleratingDown = if (delta < -2 && delta - longAvgDelta < -2) 1 else 0
        this.decceleratingDown = if (delta < 0 && (delta > shortAvgDelta || delta > longAvgDelta)) 1 else 0
        this.stable = if (delta>-3 && delta<3 && shortAvgDelta>-3 && shortAvgDelta<3 && longAvgDelta>-3 && longAvgDelta<3) 1 else 0

        val tdd7P = SafeParse.stringToDouble(sp.getString(R.string.key_tdd7, "50"))
        var tdd7Days = tddCalculator.averageTDD(tddCalculator.calculate(7, allowMissingDays = false))?.totalAmount?.toFloat() ?: 0.0f
        if (tdd7Days == 0.0f) tdd7Days = tdd7P.toFloat()
        this.tdd7DaysPerHour = tdd7Days / 24

        var tdd2Days = tddCalculator.averageTDD(tddCalculator.calculate(2, allowMissingDays = false))?.totalAmount?.toFloat() ?: 0.0f
        if (tdd2Days == 0.0f) tdd2Days = tdd7P.toFloat()
        this.tdd2DaysPerHour = tdd2Days / 24
        val tddLast4Hlocal = tdd2DaysPerHour.toDouble() * 4
        var tddDaily = tddCalculator.averageTDD(tddCalculator.calculate(1, allowMissingDays = false))?.totalAmount?.toFloat() ?: 0.0f
        if (tddDaily == 0.0f) tddDaily = tdd7P.toFloat()
        this.tddPerHour = tddDaily / 24

        var tdd24Hrs = tddCalculator.calculateDaily(-24, 0)?.totalAmount?.toFloat() ?: 0.0f
        if (tdd24Hrs == 0.0f) tdd24Hrs = tdd7P.toFloat()
        this.tdd24HrsPerHour = tdd24Hrs / 24
        val tddLast8to4Hlocal  = tdd24HrsPerHour.toDouble() * 4
        val insulin = activePlugin.activeInsulin

        val insulinDivisor = when {
            insulin.peak >= 35 -> 55 // lyumjev peak: 45
            insulin.peak > 45  -> 65 // ultra rapid peak: 55
            else               -> 75 // rapid peak: 75
        }

        val tddWeightedFromLast8H = ((1.4 * tddLast4Hlocal) + (0.6 * tddLast8to4Hlocal)) * 3
        var tdd = (tddWeightedFromLast8H * 0.33) + (tdd7Days.toDouble() * 0.34) + (tddDaily.toDouble() * 0.33)
        val dynISFadjust = SafeParse.stringToDouble(sp.getString(R.string.key_DynISFAdjust, "120")) / 100.0
        val dynISFadjusthyper = SafeParse.stringToDouble(sp.getString(R.string.key_DynISFAdjusthyper, "150")) / 100.0
        tdd = if (bg > 180) tdd * dynISFadjusthyper else tdd * dynISFadjust

        this.variableSensitivity = Round.roundTo(1800 / (tdd * (ln((glucoseStatus.glucose / insulinDivisor) + 1))), 0.1).toFloat()

        this.recentSteps5Minutes = StepService.getRecentStepCount5Min()
        this.recentSteps10Minutes = StepService.getRecentStepCount10Min()
        this.recentSteps15Minutes = StepService.getRecentStepCount15Min()
        this.recentSteps30Minutes = StepService.getRecentStepCount30Min()
        this.recentSteps60Minutes = StepService.getRecentStepCount60Min()
        this.recentSteps180Minutes = StepService.getRecentStepCount180Min()

        val beatsPerMinuteValues: List<Int>
        val beatsPerMinuteValues180: List<Int>
        val timeMillisNow = System.currentTimeMillis()
        val timeMillis5 = System.currentTimeMillis() - 5 * 60 * 1000 // 5 minutes en millisecondes
        val timeMillis180 = System.currentTimeMillis() - 180 * 60 * 1000 // 180 minutes en millisecondes

        try {
            val heartRates = repository.getHeartRatesFromTimeToTime(timeMillis5,timeMillisNow)
            beatsPerMinuteValues = heartRates.map { it.beatsPerMinute.toInt() } // Extract beatsPerMinute values from heartRates
            this.averageBeatsPerMinute = if (beatsPerMinuteValues.isNotEmpty()) {
                beatsPerMinuteValues.average()
            } else {
                80.0 // or some other default value
            }

        } catch (e: Exception) {
            // Log that watch is not connected
            this.averageBeatsPerMinute = 80.0
        }

        try {
            val heartRates180 = repository.getHeartRatesFromTimeToTime(timeMillis180,timeMillisNow)
            beatsPerMinuteValues180 = heartRates180.map { it.beatsPerMinute.toInt() } // Extract beatsPerMinute values from heartRates
            this.averageBeatsPerMinute180 = if (beatsPerMinuteValues180.isNotEmpty()) {
                beatsPerMinuteValues180.average()
            } else {
                10.0 // or some other default value
            }

        } catch (e: Exception) {
            // Log that watch is not connected
            this.averageBeatsPerMinute180 = 10.0
        }

        if (tdd2Days != 0.0f) {
            this.basalaimi = (tdd2Days / SafeParse.stringToDouble(sp.getString(R.string.key_aimiweight, "50"))).toFloat()
        } else {
            this.basalaimi = (tdd7P / SafeParse.stringToDouble(sp.getString(R.string.key_aimiweight, "50"))).toFloat()
        }

        if (tdd2Days != 0.0f) {
            this.CI = 450 / tdd2Days
        } else {
            this.CI = (450 / tdd7P).toFloat()
        }

        val choKey = SafeParse.stringToDouble(sp.getString(R.string.key_cho, "50"))
        if (CI != 0.0f && CI != Float.POSITIVE_INFINITY && CI != Float.NEGATIVE_INFINITY) {
            this.aimilimit = (choKey / CI).toFloat()
        } else {
            this.aimilimit = (choKey / profile.getIc()).toFloat()
        }

        val timenow = LocalTime.now()
        val sixAM = LocalTime.of(6, 0)
        if (averageBeatsPerMinute != 0.0) {
            this.basalaimi = when {
                averageBeatsPerMinute >= averageBeatsPerMinute180 && recentSteps5Minutes > 100 && recentSteps10Minutes > 200 -> (basalaimi * 0.65).toFloat()
                averageBeatsPerMinute180 != 80.0 && averageBeatsPerMinute > averageBeatsPerMinute180 && bg >= 130 && recentSteps10Minutes === 0 && timenow > sixAM -> (basalaimi * 1.3).toFloat()
                averageBeatsPerMinute180 != 80.0 && averageBeatsPerMinute < averageBeatsPerMinute180 && recentSteps10Minutes === 0 && bg >= 110 -> (basalaimi * 1.2).toFloat()
                else -> basalaimi
            }
        }

        this.b30upperbg = SafeParse.stringToDouble(sp.getString(R.string.key_B30_upperBG, "130"))
        this.b30upperdelta = SafeParse.stringToDouble(sp.getString(R.string.key_B30_upperdelta, "10"))
        val b30duration = SafeParse.stringToDouble(sp.getString(R.string.key_B30_duration, "30"))

        this.basalSMB = (((basalaimi * delta) / 60) * b30duration).toFloat()

        if (delta < b30upperdelta && delta > 1 && bg < b30upperbg && lastsmbtime > 20) {
            this.basaloapsaimirate = basalSMB
        } else if (predictedBg > targetBg && bg > targetBg && lastsmbtime > 20){
            this.basaloapsaimirate = basalSMB
        } else{
            this.basaloapsaimirate = 0.0f
        }

        val variableSensitivityDouble1 = variableSensitivity.toDoubleSafely()
        if (variableSensitivityDouble1 != null) {
            if (recentSteps5Minutes > 100 && recentSteps10Minutes > 200 && bg < 130 && delta < 10|| recentSteps180Minutes > 1500 && bg < 130 && delta < 10) variableSensitivity *= 1.5f
            if (recentSteps30Minutes > 500 && recentSteps5Minutes >= 0 && recentSteps5Minutes < 100 && bg < 130 && delta < 10) variableSensitivity *= 1.3f
        } else {
            variableSensitivity = profile.getIsfMgdl().toFloat()
        }

        val getlastBolusSMB = repository.getLastBolusRecordOfTypeWrapped(Bolus.Type.SMB).blockingGet()
        val lastBolusSMBTime = if (getlastBolusSMB is ValueWrapper.Existing) getlastBolusSMB.value.timestamp else 0L
        this.lastsmbtime = ((now - lastBolusSMBTime) / (60 * 1000)).toDouble().roundToInt().toLong().toInt()

        this.maxIob = sp.getDouble(R.string.key_openapssmb_max_iob, 5.0).toFloat()
        this.maxSMB = sp.getDouble(R.string.key_openapsaimi_max_smb, 1.0).toFloat()

        // profile.dia
        val abs = iobCobCalculator.calculateAbsoluteIobFromBaseBasals(System.currentTimeMillis())
        val absIob = abs.iob
        val absNet = abs.netInsulin
        val absBasal = abs.basaliob

        aapsLogger.debug(LTag.APS, "IOB options : bolus iob: ${iobCalcs.iob} basal iob : ${iobCalcs.basaliob}")
        aapsLogger.debug(LTag.APS, "IOB options : calculateAbsoluteIobFromBaseBasals iob: $absIob net : $absNet basal : $absBasal")
        val tddDouble = tdd.toDoubleSafely()
        val glucoseDouble = glucoseStatus.glucose.toDoubleSafely()
        val insulinDivisorDouble = insulinDivisor.toDoubleSafely()

        if (tddDouble != null && glucoseDouble != null && insulinDivisorDouble != null) {
            variableSensitivity = (1800 / (tdd.toDouble() * (ln((glucoseStatus.glucose / insulinDivisor) + 1)))).toFloat()

            // Ajout d'un log pour vérifier la valeur de variableSensitivity après le calcul
            val variableSensitivityDouble2 = variableSensitivity.toDoubleSafely()
            if (variableSensitivityDouble2 != null) {
                if (recentSteps5Minutes > 100 && recentSteps10Minutes > 200 && bg < 130 && delta < 10|| recentSteps180Minutes > 1500 && bg < 130 && delta < 10) variableSensitivity *= 1.5f
                if (recentSteps30Minutes > 500 && recentSteps5Minutes >= 0 && recentSteps5Minutes < 100 && bg < 130 && delta < 10) variableSensitivity *= 1.3f
            }
        } else {
            variableSensitivity = profile.getIsfMgdl().toFloat()
        }

        this.predictedBg = predictFutureBg(bg, iob, variableSensitivity, cob, CI)
        this.profile = JSONObject()
        this.profile.put("max_iob", maxIob)
        this.profile.put("dia", min(profile.dia, 3.0))
        this.profile.put("type", "current")
        this.profile.put("max_daily_basal", profile.getMaxDailyBasal())
        this.profile.put("max_basal", maxBasal)
        this.profile.put("min_bg", minBg)
        this.profile.put("max_bg", maxBg)
        this.profile.put("target_bg", targetBg)
        this.profile.put("predictedBg", predictedBg)
        this.profile.put("carb_ratio", CI)
        this.profile.put("sens", variableSensitivity)
        this.profile.put("max_daily_safety_multiplier", sp.getInt(R.string.key_openapsama_max_daily_safety_multiplier, 3))
        this.profile.put("current_basal_safety_multiplier", sp.getDouble(R.string.key_openapsama_current_basal_safety_multiplier, 4.0))
        this.profile.put("skip_neutral_temps", true)
        this.profile.put("current_basal", basalRate)
        this.profile.put("temptargetSet", tempTargetSet)
        this.profile.put("autosens_adjust_targets", sp.getBoolean(R.string.key_openapsama_autosens_adjusttargets, true))

        if (profileFunction.getUnits() == GlucoseUnit.MMOL) {
            this.profile.put("out_units", "mmol/L")
        }

        val tb = iobCobCalculator.getTempBasalIncludingConvertedExtended(now)
        currentTemp = JSONObject()
        currentTemp.put("temp", "absolute")
        currentTemp.put("duration", tb?.plannedRemainingMinutes ?: 0)
        currentTemp.put("rate", tb?.convertedToAbsolute(now, profile) ?: 0.0)

        // as we have non default temps longer than 30 minutes
        if (tb != null) currentTemp.put("minutesrunning", tb.getPassedDurationToTimeInMinutes(now))

        iobData = iobCobCalculator.convertToJSONArray(iobArray)
        this.glucoseStatus = JSONObject()
        this.glucoseStatus.put("glucose", glucoseStatus.glucose)

        if (sp.getBoolean(R.string.key_always_use_shortavg, false)) {
            this.glucoseStatus.put("delta", glucoseStatus.shortAvgDelta)
        } else {
            this.glucoseStatus.put("delta", glucoseStatus.delta)
        }
        this.glucoseStatus.put("short_avgdelta", glucoseStatus.shortAvgDelta)
        this.glucoseStatus.put("long_avgdelta", glucoseStatus.longAvgDelta)
        this.mealData = JSONObject()
        this.mealData.put("carbs", mealData.carbs)
        this.mealData.put("mealCOB", mealData.mealCOB)
        if (constraintChecker.isAutosensModeEnabled().value()) {
            autosensData.put("ratio", autosensDataRatio)
        } else {
            autosensData.put("ratio", 1.0)
        }
    }

    private fun Number.toDoubleSafely(): Double? {
        val doubleValue = this.toDouble()
        return doubleValue.takeIf { !it.isNaN() && !it.isInfinite() }
    }

    private fun parseNotes(startMinAgo: Int, endMinAgo: Int): String {
        val olderTimeStamp = now - endMinAgo * 60 * 1000
        val moreRecentTimeStamp = now - startMinAgo * 60 * 1000
        var notes = ""

        recentNotes?.forEach { note ->
            if (note.timestamp > olderTimeStamp
                && note.timestamp <= moreRecentTimeStamp
                && !note.note.lowercase().contains("low treatment")
                && !note.note.lowercase().contains("less aggressive")
                && !note.note.lowercase().contains("more aggressive")
                && !note.note.lowercase().contains("too aggressive")
            ) {
                notes += if(notes.isEmpty()) "" else " "
                notes += note.note
            }
        }

        notes = notes.lowercase()
        notes.replace(","," ")
        notes.replace("."," ")
        notes.replace("!"," ")
        notes.replace("a"," ")
        notes.replace("an"," ")
        notes.replace("and"," ")
        notes.replace("\\s+"," ")

        return notes
    }

    init {
        injector.androidInjector().inject(this)
    }
}


