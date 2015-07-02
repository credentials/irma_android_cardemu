# Bouncycastle vs. Spongycastle in Android

Several of our projects (notably [`credentials_idemix`](https://github.com/credentials/idemix_terminal), [`idemix_terminal`](https://github.com/credentials/credentials_idemix) and [`irma_android_cardemu`](https://bitbucket.org/wlueks/irma_android_cardemu/)), along with [`scuba`](http://sourceforge.net/projects/scuba/) and [`jmrtd`](http://sourceforge.net/projects/jmrtd/), use the commonly used library [`org.bouncycastle`](https://www.bouncycastle.org/java.html) for some of the cryptography that they perform. Unfortunately, Android apparently ships with a cut-down and crippled version (see [this issue](https://code.google.com/p/android/issues/detail?id=3280)) that can cause some subtle problems. For example, in the CardEmu app, the `jmrtd` library attempts to use the MAC `ISO9797Alg3Mac` when talking to the passport. This MAC [should be included](http://www.cs.berkeley.edu/~jonah/bc/org/bouncycastle/crypto/macs/ISO9797Alg3Mac.html) in Bouncy Castle but apparently isn't in the Android implementation, so that the application crashes.


## spongycastle
To solve exactly these kinds of problems, a version of the Bouncy Castle library called [Spongy Castle](https://rtyley.github.io/spongycastle/) was created, in which the name `org.bouncycastle` has been replaced by `org.spongycastle` so that it can live besides Bouncy Castle. Other than that the two libraries are completely identical.


## Possible solutions
Although much of the internet seems to agree on the fact that Android's Bouncy Castle is broken, it is surprisingly difficult to find out which Android version includes exactly what parts of Bouncy Castle. This means that we cannot be certain that the parts of Bouncy Castle that we need are present on the phone. Thus ideally, we should either avoid depending on Bouncy Castle, or test all functionality on all relevant Android versions.

### Using just the provider from SpongyCastle
For the moment, in the CardEmu app we have simply added the security provider from SpongyCastle like so:
```java
Security.addProvider(new org.spongycastle.jce.provider.BouncyCastleProvider());
```
This security provider provides the `ISO9797Alg3Mac` MAC like it should. On Android 5.0.1 (Cyanogenmod) this works fine with the upstream `jmrtd`, but more testing is certainly warranted.

### Replacing BouncyCastle with SpongyCastle
If the above approach turns out to break on earlier Android versions, then we should rely more on Spongy Castle and less on Bouncy Castle. The simple approach would be to simply replace `org.bouncycastle` with `org.spongycastle` accross all .java files. However, it would probably be more practical to use [JarJar](https://code.google.com/p/jarjar/): a tool that can do exactly such a substitution automatically just before packaging the .jar file. See for example [this](https://github.com/vRallev/jarjar-gradle), or [this](http://www.unwesen.de/2011/06/12/encryption-on-android-bouncycastle/).
