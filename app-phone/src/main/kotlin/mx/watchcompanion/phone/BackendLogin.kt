package mx.watchcompanion.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import mx.watchcompanion.core.session.LoginResult

/**
 * Login de un backend (Velora o Nexora) en app-phone, cada uno con su sesión propia (WC-ADR-013). La contraseña no se
 * guarda: se envía una vez y se olvida (solo persiste el refresh token cifrado). El servidor solo se cambia en debug.
 *
 * @param backend nombre visible: «Velora» o «Nexora».
 */
@Composable
fun BackendLogin(
    backend: String,
    defaultServer: String,
    showServerField: Boolean,
    busy: Boolean,
    error: LoginResult?,
    onSubmit: (server: String, email: String, password: String) -> Unit,
    onCancel: () -> Unit,
) {
    var server by rememberSaveable(backend) { mutableStateOf(defaultServer) }
    var email by rememberSaveable { mutableStateOf("") }
    // Sin rememberSaveable a propósito: la contraseña no va al estado guardado de la actividad.
    var password by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.login_title, backend), style = MaterialTheme.typography.headlineSmall)
        if (showServerField) {
            OutlinedTextField(
                value = server,
                onValueChange = { server = it },
                label = { Text(stringResource(R.string.login_server)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text(stringResource(R.string.login_email)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(R.string.login_password)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let { Text(loginErrorText(it, backend), color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onSubmit(server.trim(), email.trim(), password) },
                enabled = !busy && email.isNotBlank() && password.isNotEmpty(),
            ) { Text(stringResource(R.string.login_submit)) }
            TextButton(onClick = onCancel, enabled = !busy) { Text(stringResource(R.string.login_cancel)) }
        }
    }
}

@Composable
private fun loginErrorText(result: LoginResult, backend: String): String = when (result) {
    LoginResult.InvalidCredentials -> stringResource(R.string.login_error_credentials)
    is LoginResult.TooManyAttempts -> result.retryAfterSeconds
        ?.let { stringResource(R.string.login_error_too_many, it.toInt()) }
        ?: stringResource(R.string.login_error_too_many_generic)
    LoginResult.InvalidInput -> stringResource(R.string.login_error_input)
    LoginResult.InsecureServer -> stringResource(R.string.login_error_insecure)
    LoginResult.Unreachable -> stringResource(R.string.login_error_unreachable, backend)
    LoginResult.Unavailable, LoginResult.Success -> stringResource(R.string.login_error_unavailable, backend)
}
