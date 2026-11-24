# Asistente Gallo

App de Android que escucha mientras manejás (o cuando decís la palabra clave), y con Claude saca los compromisos que acordaste: los ordena en **Él solo**, **Juntos** y **Yo**, prepara mensajes de WhatsApp y los pasa al calendario.

- `app/src/main/java/.../ListenService.java`: escucha en segundo plano y palabra clave.
- `app/src/main/java/.../MainActivity.java`: pantalla, llamada a la API de Claude y actualizaciones.
- `app/src/main/assets/index.html`: la interfaz.

Para publicar una versión: subir el APK a `update/AsistenteGallo.apk` y subir `versionCode` en `update/version.json`. La app lo lee al abrir y ofrece actualizar.
La clave de Claude y los compromisos quedan solo en el celular.
