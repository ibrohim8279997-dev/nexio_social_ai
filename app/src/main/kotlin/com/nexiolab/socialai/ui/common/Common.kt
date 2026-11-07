package com.nexiolab.socialai.ui.common

import android.content.Context
import androidx.activity.compose.LocalActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nexiolab.socialai.AppContainer
import com.nexiolab.socialai.NexioApp
import com.nexiolab.socialai.domain.i18n.Msg
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Currency
import java.util.Locale

val Context.container: AppContainer get() = (applicationContext as NexioApp).container

val LocalSnackbar = compositionLocalOf<SnackbarHostState> { error("No snackbar host") }

@Composable
inline fun <reified VM : ViewModel> appViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM {
    val container = LocalContext.current.container
    return viewModel(key = key, factory = viewModelFactory { initializer { create(container) } })
}

/** Runs user actions with a busy flag and turns any failure into a visible message. */
abstract class BaseViewModel : ViewModel() {
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val busy = MutableStateFlow(false)

    protected fun act(done: String? = null, block: suspend () -> Unit) {
        viewModelScope.launch {
            busy.value = true
            try {
                block()
                done?.let(messages::tryEmit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messages.tryEmit(e.message ?: Msg.SOMETHING_WENT_WRONG())
            } finally {
                busy.value = false
            }
        }
    }

    /** For saves that happen while typing: no busy flag, so buttons do not flicker. */
    protected fun quiet(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messages.tryEmit(e.message ?: Msg.SOMETHING_WENT_WRONG())
            }
        }
    }

    protected fun <T> Flow<T>.state(initial: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)
}

@Composable
fun Messages(vm: BaseViewModel) {
    val snackbar = LocalSnackbar.current
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
}

/** Confirms the owner before sensitive actions. Without a device lock there is nothing to confirm against. */
class Authenticator(private val activity: FragmentActivity) {
    private val allowed = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    val available: Boolean
        get() = BiometricManager.from(activity).canAuthenticate(allowed) == BiometricManager.BIOMETRIC_SUCCESS

    fun confirm(title: String, onSuccess: () -> Unit) {
        if (!available) return onSuccess()
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder().setTitle(title).setAllowedAuthenticators(allowed).build(),
        )
    }
}

@Composable
fun rememberAuthenticator(): Authenticator = Authenticator(LocalActivity.current as FragmentActivity)

@Composable
fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    supporting: String? = null,
    visual: VisualTransformation = VisualTransformation.None,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        supportingText = supporting?.let { { Text(it) } },
        visualTransformation = visual,
        // Secrets must stay out of the keyboard's dictionary and suggestions.
        keyboardOptions = if (visual is PasswordVisualTransformation) {
            KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)
        } else {
            KeyboardOptions.Default
        },
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * A field whose value is stored as it is typed. The text stays local while typing, because a
 * round trip through the database would move the cursor and could drop characters.
 */
@Composable
fun LiveField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    supporting: String? = null,
) {
    var text by remember { mutableStateOf(value) }
    var edited by remember { mutableStateOf(false) }
    LaunchedEffect(value) { if (!edited) text = value }
    Field(label, text, { text = it; edited = true; onChange(it) }, modifier, singleLine, supporting)
}

@Composable
fun BusyButton(text: String, busy: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick, modifier = modifier.fillMaxWidth(), enabled = enabled && !busy) {
        if (busy) CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp) else Text(text)
    }
}

@Composable
fun Gap(size: Int = 8) = Spacer(Modifier.height(size.dp))

object Format {
    private val zone get() = ZoneId.systemDefault()
    // Built per call so a language change applies without restarting the process.
    private val dateTime get() = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(Locale.getDefault())
    private val date get() = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault())

    fun dateTime(millis: Long): String = Instant.ofEpochMilli(millis).atZone(zone).format(dateTime)
    fun date(millis: Long): String = Instant.ofEpochMilli(millis).atZone(zone).format(date)

    fun money(amount: Double, currency: String = "USD"): String =
        NumberFormat.getCurrencyInstance(Locale.getDefault()).apply {
            this.currency = runCatching { Currency.getInstance(currency) }.getOrDefault(Currency.getInstance("USD"))
            maximumFractionDigits = if (amount != 0.0 && kotlin.math.abs(amount) < 0.1) 4 else 2
        }.format(amount)

    fun number(value: Long): String = NumberFormat.getIntegerInstance(Locale.getDefault()).format(value)

    fun bytes(value: Long): String = when {
        value >= 1L shl 30 -> "%.1f GB".format(value / (1L shl 30).toDouble())
        value >= 1L shl 20 -> "%.1f MB".format(value / (1L shl 20).toDouble())
        value >= 1L shl 10 -> "%.0f KB".format(value / (1L shl 10).toDouble())
        else -> "$value B"
    }
}
