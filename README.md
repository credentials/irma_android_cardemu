# IRMA Android app

An IRMA credential manager for Android. Credentials can be issued to it, after which it can disclose the attributes contained in those credentials. New credentials can be tied to other existing credentials, or to other trusted data using self-enrollment processes.

## Prerequisites

This application has the following dependencies.  All these dependencies will be automatically downloaded by gradle when building or installing the library.

External dependencies:

 * [Android Asynchronous HTTP Client](http://loopj.com/android-async-http/)
 * Android support v4
 * [Google GSON](https://code.google.com/p/google-gson/), for serializing to and from JSON
 * [ACRA](https://github.com/ACRA/acra/), the Application Crash Reports for Android
 * [ZXing](https://github.com/zxing/zxing), for scanning QR codes

Internal dependencies:

 * [irma_api_common](https://github.com/credentials/irma_api_common/), The common classes for the verification and issuance protocol

The build system depends on gradle version at least 2.1, which is why we've included the gradle wrapper, so you always have the right version.

## irma_configuration

The credential definitions, issuer information and public keys must be compiled into the app in a folder called `irma_configuration` within the `assets` folder. For example, in order to install the [`irma-demo`](https://github.com/credentials/irma-demo-schememanager) and [`pbdf`](https://github.com/credentials/pbdf-schememanager) scheme managers:

    cd src/main/assets
    mkdir irma_configuration
    cd irma_configuration
    git clone https://github.com/credentials/irma-demo-schememanager irma-demo
    git clone https://github.com/credentials/pbdf-schememanager pbdf

## Building

Run

    ./gradlew assemble

this will create the required `.apk`s and place them in `build/outputs/apk`.

## Installing on your own device

You can install the application to you own device by running

    ./gradlew installDebug
