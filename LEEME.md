# Metal HIIT para Android

App de HIIT con tu propia música, voz de locutor metal y pantalla siempre encendida.

GitHub compila el APK por ti. Revisa la guía en el chat de Claude para ver los pasos.

## Estructura
- `app/src/main/assets/index.html`: interfaz y temporizador
- `app/src/main/java/.../MainActivity.kt`: pantalla siempre encendida y selector de música
- `app/src/main/java/.../MetalVoice.kt`: voz metal (TTS del teléfono + distorsión + reverb)
- `.github/workflows/build.yml`: compilación automática del APK
