# irma_android_cardemu

This android app emulates an IRMA card. Credentials can be issued to it using self-enrollment.

## Prerequisites

This application has the following dependencies.  All these dependencies will be automatically downloaded by gradle when building or installing the library.

External depenencies:

 * [Android Asynchronous HTTP Client](http://loopj.com/android-async-http/)
 * Android support v4
 * [Google GSON](https://code.google.com/p/google-gson/)

Internal dependencies:

 * [irma_android_library](https://github.com/credentials/irma_android_library/), The IRMA android library
 * [Scuba](https://github.com/credentials/scuba), The smartcard abstraction layer, uses `scuba_sc_android` and `scuba_smartcard`
 * [jmrtd](http://jmrtd.org/), A library for easy reading of ICAO documents. This should become an external library, but untill it is, this version was changed from the original build using the JarJar tool (https://code.google.com/p/jarjar/) to change any occurences of Scuba to our Scuba version.

Gradle will take care of the transitive dependencies. However, you must make sure that you [build and install the idemix_library](https://github.com/credentials/idemix_library/) yourself.

The build system depends on gradle version at least 2.1, which is why we've included the gradle wrapper, so you always have the right version.

## irma_configure

Make sure to link a version of irma_configuration into `src/main/assets/`

## Including credentials

If you include a Gson-serialized `IRMACard` instance (see the [idemix_terminal](https://github.com/credentials/idemix_terminal/) project) in the `src/main/assets/` folder, then the app will allow you to load the credentials contained in this card through the context menu.

## Building

Run

    ./gradlew assemble

this will create the required `.apk`s and place them in `build/outputs/apk`.

## Installing on your own device

You can install the application to you own device by running

    ./gradlew installDebug
