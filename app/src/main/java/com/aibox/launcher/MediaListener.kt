package com.aibox.launcher

import android.service.notification.NotificationListenerService

/**
 * Servicio vacío: solo sirve para que Android permita leer las sesiones de música activas
 * (título, artista, play/pausa). El usuario debe darle "Acceso a notificaciones" una vez.
 */
class MediaListener : NotificationListenerService()
