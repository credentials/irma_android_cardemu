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

## irma_configure

Make sure to link a version of `irma_configuration` into `src/main/assets`. For example, if `irma_android_cardemu` and `irma_configuration` are checked out in the same directory, you can do

    ln -s ../../../../irma_configuration

## Building

Run

    ./gradlew assemble

this will create the required `.apk`s and place them in `build/outputs/apk`.

## Installing on your own device

You can install the application to you own device by running

    ./gradlew installDebug
