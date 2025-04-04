package app.aaps.wear.interaction.utils

import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.wear.R
import app.aaps.wear.databinding.ActionEditplusminBinding
import app.aaps.wear.databinding.ActionEditplusminQuickleftyBinding
import app.aaps.wear.databinding.ActionEditplusminQuickleftyMultiBinding
import app.aaps.wear.databinding.ActionEditplusminQuickrightyBinding
import app.aaps.wear.databinding.ActionEditplusminQuickrightyMultiBinding
import app.aaps.wear.databinding.ActionEditplusminSportDutyPercentageBinding
import app.aaps.wear.databinding.ActionEditplusminViktoriaBinding

/**
 * EditPlusMinusViewAdapter binds both ActionEditplusminBinding variants shared attributes to one common view adapter.
 * Requires at least one of the ViewBinding as a parameter. Recommended to use the factory object to create the binding.
 */
class EditPlusMinusPercentageViewAdapter(
    eD: ActionEditplusminBinding?,
    // eDP: ActionEditplusminMultiBinding?,
    eDP: ActionEditplusminSportDutyPercentageBinding?,
    eQL: ActionEditplusminQuickleftyBinding?,
    eQLP: ActionEditplusminQuickleftyMultiBinding?,
    eQR: ActionEditplusminQuickrightyBinding?,
    eQRP: ActionEditplusminQuickrightyMultiBinding?,
    eV: ActionEditplusminViktoriaBinding?
) {

    init {
        if (eD == null && eDP == null && eQL == null && eQLP == null && eQR == null && eQRP == null && eV == null) {
            throw IllegalArgumentException("Require at least on Binding parameter")
        }
    }

    private val errorMessage = "Missing require View Binding parameter"

    val label =
        eD?.label ?: eDP?.label ?: eQL?.label ?: eQLP?.label ?: eQR?.label ?: eQRP?.label ?: eV?.label
        ?: throw IllegalArgumentException(errorMessage)

    val editText =
        eD?.editText ?: eDP?.editText ?: eQL?.editText ?: eQLP?.editText ?: eQR?.editText ?: eQRP?.editText ?: eV?.editText
        ?: throw IllegalArgumentException(errorMessage)

    val minButton =
        eD?.minButton ?: eDP?.minButton ?: eQL?.minButton ?: eQLP?.minButton ?: eQR?.minButton ?: eQRP?.minButton ?: eV?.minButton
        ?: throw IllegalArgumentException(errorMessage)

    val plusButton1 =
        eD?.plusButton1 ?: eDP?.plusButton1 ?: eQL?.plusButton1 ?: eQLP?.plusButton1 ?: eQR?.plusButton1 ?: eQRP?.plusButton1 ?: eV?.plusButton1
        ?: throw IllegalArgumentException(errorMessage)

    val btnPreset3 = eDP?.btnPreset3 // ?: eQLP?.btnPreset3 ?: eQRP?.btnPreset3
    val btnPreset2 = eDP?.btnPreset2 // ?: eQLP?.btnPreset2 ?: eQRP?.btnPreset2
    val btnPreset1 = eDP?.btnPreset1 // ?: eQLP?.btnPreset1 ?: eQRP?.btnPreset1

    val root = eD?.root ?: eDP?.root ?: eQL?.root ?: eQLP?.root ?: eQR?.root ?: eQRP?.root ?: eV?.root
        ?: throw IllegalArgumentException(errorMessage)


    companion object {
        fun getViewAdapter(sp: SP, context: Context, container: ViewGroup, multiple: Boolean = false): EditPlusMinusPercentageViewAdapter {
            val inflater = LayoutInflater.from(context)
            val keyInputDesign = sp.getInt(R.string.key_input_design, 1)
            Log.d("EditPlusMinusPercentageViewAdapter", "keyInputDesign = $keyInputDesign")
            return when (keyInputDesign) {
                2    -> {
                    if (multiple) {
                        val bindLayout = ActionEditplusminQuickrightyMultiBinding.inflate(inflater, container, false)
                        EditPlusMinusPercentageViewAdapter(null, null, null, null, null, bindLayout, null)

                    } else {
                        val bindLayout = ActionEditplusminQuickrightyBinding.inflate(inflater, container, false)
                        EditPlusMinusPercentageViewAdapter(null, null, null, null, bindLayout, null, null)
                    }
                }

                3    -> {
                    if (multiple) {
                        val bindLayout = ActionEditplusminQuickleftyMultiBinding.inflate(inflater, container, false)
                        EditPlusMinusPercentageViewAdapter(null, null, null, bindLayout, null, null, null)
                    } else {
                        val bindLayout = ActionEditplusminQuickleftyBinding.inflate(inflater, container, false)
                        EditPlusMinusPercentageViewAdapter(null, null, bindLayout, null, null, null, null)
                    }
                }

                4    -> {
                    val bindLayout = ActionEditplusminViktoriaBinding.inflate(inflater, container, false)
                    EditPlusMinusPercentageViewAdapter(null, null, null, null, null, null, bindLayout)
                }

                else -> {
                    if (multiple) {
                        val bindLayout = ActionEditplusminSportDutyPercentageBinding.inflate(inflater, container, false)
                        EditPlusMinusPercentageViewAdapter(null, bindLayout, null, null, null, null, null)

                    } else {
                        val bindLayout = ActionEditplusminBinding.inflate(inflater, container, false)
                        EditPlusMinusPercentageViewAdapter(bindLayout, null, null, null, null, null, null)
                    }
                }
            }
        }
    }
}
