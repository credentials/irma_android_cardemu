
IRMA phone functionality test
=============================

Welcome to the first public functionality test of the IRMA phone app that brings the IRMA functionality to your Android phone.
On this page you can find information on the IRMA phone app, or immediately go to [download](#install).

What can the IRMA phone app do for me?
--------------------------------------
The IRMA (I Reveal My Attributes) technology was originally created for [smart cards](https://www.irmacard.org/). Now it is also available as an Android app! Within the IRMA technology users have certified statements about them, such as "I am over 18", "I am entitled to access ..." or "my name is ...". These statements are called *attributes*.
Users can use these attributes to authenticate to services.
For instance, if you order something in an online store, usually this store only needs to know your address and possibly whether you are of legal age.
In practice, online stores often request your name, date of birth and any other pieces of data they want.
The IRMA technology allows users to reveal only a minimal set of attributes about themselves.
Not only the users but also the online stores (and other verifiers) benefit from the IRMA technology. Attributes presented to them are reliable, signed statements by trusted parties. For instance, Amazon can have more trust in an IRMA attribute proving someone's address, than in the address typed in a text field by a user.
A detailed explanation of the IRMA technology can be found [here](https://www.irmacard.org/irma/).

Since we are currently having a functionality test, the actual ways in which you can use the IRMA phone app are limited.
We have a few toy examples to show how IRMA technology would work in practice on [demo.irmacard.org](https://demo.irmacard.org/).
Also, if you are interested in attending the upcoming [IRMA meeting](https://www.irmacard.org/events/) (6 November, 2015), you can now use the IRMA phone app to [register](TODO) for this event.

How can I participate?
----------------------
[Download](#install) and start using the app! (Note that the app only works on Android phones for technical reasons.)
This is a functionality test, so we are very curious how you experience IRMA on your phone.
If you have any feedback to share with us, please send us an [e-mail](), or come and tell us at the upcoming [IRMA meeting](https://www.irmacard.org/events/).

How does it work?
-----------------
After [downloading and installing](#install) the IRMA phone app, you can start using it.

1. The *enrolment* is an initialisation process to set up your personal IRMA environment
2. Having an initial set of attributes, you are able to *use the IRMA app for authentication*

### <a name=sse></a>Personalising the app
When you first start the IRMA phone app, it does not yet contain any attributes about you.
Therefore, you first need to obtain an initial set of attributes. To better model a real-world application, we use authentic data for you to start using the IRMA technology. A relation is being built between one of your identity documents and your IRMA phone app.

First, by choosing the menu option *Enroll* in the IRMA app, you start the self enrolment procedure.
After pressing "continue" your app will transmit your IMSI number, a unique number used by your mobile phone provider, to the IRMA server.
(In the future this enables your mobile operator to ensure that the phone being used actually belongs to you.)

You are then asked to fill in the document number, your date of birth and the expiry date of your electronic identity document (currently we only support Dutch identity cards and passports).
This is required for your phone to be able to communicate with your identity document.

After pressing the continue button, the app will request you to hold your identity document against your phone and start communicating with it.
A bar indicates the progress of the reading of your identity document.
The IRMA server then verifies if your identity document is genuine.
In this case, your IRMA phone app will receive several attributes deduced from your passport (*e.g.,* your name and whether you are over 18).

You now see an overview of your attributes in the app's main view.

### Using the app 
The main purpose of the IRMA phone app is to allow you to do online authentication at service providers. Each authentication process reveals a subset of your available attributes.

After [obtaining your attributes](#sse), you can use the app for some experimental applications. Most importantly, you can **[register] (TODO) for the upcoming [IRMA meeting](https://www.irmacard.org/events/)**.
Furthermore, you can also play around with the IRMA functionality on the [IRMA demo website](https://demo.irmacard.org).
All of these functions will require you to disclose some (but not all!) attributes to a verifier.

Let's look at how this works:

TODO: screen shots of how to use the IRMA phone app


Security implications
--------------------
IRMA is a privacy enhancing technology. The IRMA infrastructure will not leak any information about your identity to other parties, except for that which you allow.
The IRMA team cares passionately about your privacy. However, since this is a first public functionality test there are some caveats.

### App security
This public test is purely a functionality test.
The current version of the app is yet to be strengthened with respect to security!
In practice this means that if attackers got their hands on your phone, they would likely be able to obtain your attributes.
As we are currently only in a test system, no harm can come of using these attributes, and likely your phone will contain more privacy sensitive data than these attributes, but it is good to be aware of this.

### App privacy
During the self-enrolment, our server receives data from your identity document. This data is both used to verify whether the presented document is genuine and to issue valid attributes to your IRMA phone app.
This data is not stored in any way.
Furthermore, while using IRMA for authentication, only the attributes you reveal will be readable by the server. Again this data will not be stored.
The only exception to this is registering for the upcoming IRMA meeting, for which we will store your name.

### Android permissions
The IRMA phone app requires several permissions, which Android will ask you to approve upon installing the app.
We attempted to keep the number of permissions required to a minimum.
Here we explain why we require each permission:

* NFC, the IRMA phone app uses NFC to read your identity document during [self enrolment](#sse).
* INTERNET, Internet connection is needed, as both enrolment and verifications happen online.
* CAMERA, camera access is required to scan the [QR code](#QR_code) during authentication.
* VIBRATE, your phone will vibrate when a connection with your electronic identity document is established.
* READ PHONE STATE, allows the app to read your IMSI number, which will be an integral part of the enrolment procedure. This permission also allows the collection of some debugging information in case of a [crash](#crash).
* ACCESS NETWORK STATE, this permission allows the app to only send metrics data when there is a WiFi connection.

### <a name=crasg></a>Crash reports
As the IRMA phone app is still under active development, things can go wrong. We have not yet tested this software on a wide variety of devices (that's one of the goals of this public test).
When the app crashes, or something goes wrong internally, the app will send a *crash report* to our servers.
Although we have done our best to ensure that the contents of your attributes will never be included in these crash reports, other privacy sensitive values might be included, such as the exact brand and type of your phone, the version of your mobile OS and the time of the crash.
We will treat this information as sensitive data, and remove it as soon as we no longer need it.

Also, please be advised that these crash reports are transmitted even when you have no WiFi connection.
This means that even though the crash reports are quite small (about the size of an average e-mail), their transmission can incur some mobile data costs.

### Metrics
We also gather some statistics and metrics about the use of the IRMA phone app.
This data (such as how long it takes your phone to perform the necessary computation for proving your attributes) helps us to further improve the system.
These details are stored on your phone and transmitted to us periodically in batches, only over WiFi connections.
We have taken care to make sure that no privacy sensitive details are revealed this way.


What do I need?
---------------
### System requirements
To use the IRMA phone app, you will need a smart phone running Android 4.1 (Jelly Bean) or higher.
Your phone also needs to be equipped with NFC and a camera, though these are very standard features of most Android phones.
Furthermore, to obtain your initial attributes you will need a Dutch electronic identity card or passport.
We do not yet support the Dutch electronic driving licence or electronic documents from other countries.

### <a name=install></a>Download and installation
Since this is a limited public functionality test, we have not yet made the app available in the Android app store.
In order to install the app you will have to enable the security option "unknown sources", which allows you to install apps from outside of the Android market.
Then simply browse to [TODO](TODO) on your phone, to download and install the app.
Enjoy!

### ???Technical assistance
???Do we want to give an e-mail address for people to be able to ask help?

