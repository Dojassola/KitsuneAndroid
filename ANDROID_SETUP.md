# Preparação do ambiente para o Kitsune Android

O Kitsune Android será criado como um projeto e repositório separado, baseado nas
funcionalidades do Kitsune desktop:

```text
C:\Users\dojas\Documents\projetos\
  anime\              Kitsune desktop
  kitsune-android\    Kitsune Android
```

## 1. Instalar o Android Studio

Baixe a versão estável mais recente no site oficial:

- <https://developer.android.com/studio/install>

Durante a instalação, mantenha selecionados:

- Android SDK;
- Android SDK Platform;
- Android Virtual Device;
- Android Emulator.

O Android Studio já inclui o JDK adequado. Não instale Java separadamente.

## 2. Instalar os componentes do SDK

No Android Studio, abra:

```text
More Actions → SDK Manager
```

Em **SDK Platforms**, instale a plataforma Android estável mais recente.

Em **SDK Tools**, instale:

- Android SDK Build-Tools;
- Android SDK Platform-Tools;
- Android SDK Command-line Tools;
- Android Emulator;
- NDK (Side by side);
- CMake.

NDK e CMake serão usados posteriormente pelo cliente torrent e pelo player nativo.

Documentação oficial:

- <https://developer.android.com/studio/projects/install-ndk>

## 3. Preparar um celular ou emulador

### Celular físico

No aparelho Android:

```text
Configurações → Sobre o telefone
→ tocar 7 vezes em “Número da versão”
→ Opções do desenvolvedor
→ Depuração USB
```

Depois, conecte o aparelho ao computador por USB e autorize a depuração.

Documentação oficial:

- <https://developer.android.com/studio/run/device>

### Emulador

Crie um aparelho pelo **Device Manager** do Android Studio. A virtualização de
hardware deve estar habilitada na BIOS e no Windows.

## 4. Ferramentas que não precisam ser instaladas separadamente

Não instale agora:

- Java ou JDK;
- Gradle;
- Kotlin;
- libtorrent;
- mpv;
- React Native;
- Capacitor;
- Flutter;
- Rust.

Gradle e Kotlin serão controlados pelo próprio projeto. As bibliotecas nativas
serão configuradas somente quando o código Android for criado.

## 5. Espaço em disco

Reserve aproximadamente 20 GB para Android Studio, SDK, NDK e um emulador.

## Projeto criado

O projeto Android está neste diretório:

```text
C:\Users\dojas\Documents\projetos\anime-android
```

Abra essa pasta no Android Studio. O aplicativo usa Kotlin, Jetpack Compose e o
identificador `com.kitsuneandroid`.
