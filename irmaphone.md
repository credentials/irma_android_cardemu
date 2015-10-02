
IRMA phone functionality test
=============================

Welcome to the first public functionality test of IRMA phone, an Android app which brings the IRMA functionality to your phone.
On this page you can find information on the IRMA phone app for you to read, or immediately go to [download](#install)

What can the IRMA phone app do for me?
--------------------------------------
The IRMA phone app offers the IRMA (I Reveal My Attributes) technology originally created for [smart cards](https://www.irmacard.org/) in the form of an Android app.
Within the IRMA technology users have certified statements about them, such as "I am over 18", or "my name is ...". These statements are called *attributes*.
Users can use these attributes to authenticate to services.
For instance, if you order something in an online store, usually this store only needs to know your address and possibly whether you are of legal age.
In practice, online stores often ask for your name, date of birth and any other data they want.
IRMA technology allows users to only reveal a minimal set of attributes about themselves.
Vice versa, IRMA technology also benefits the online stores (and other verifiers), as the attributes they get presented are actually signed statements by trusted parties: Amazon can have more trust in an IRMA attribute proving someone's address, than in the address typed in a text field by a user.
A detailed explanation of the IRMA technology can be found [here](https://www.irmacard.org/irma/).

Since we are currently having a functionality test, the actual ways in which you can use the IRMA phone app are limited.
We have several toy examples to show how IRMA technology would work in practice on [demo.irmacard.org](https://demo.irmacard.org/).
Also, if you are interested in attending the upcoming [IRMA meeting](https://www.irmacard.org/events/) (November the 6th), you can now use the IRMA phone app to [register](TODO) for this event.

What would we like you to do?
-----------------------------
[Download](#install) and start using the app!
This is a functionality test, so we are very curious how you experience IRMA on your phone.
If you have any feedback to share with us, please send us an [e-mail](), or come tell us at the upcoming [IRMA meeting](https://www.irmacard.org/events/).

How does it work?
-----------------
After [downloading and installing](#install) the app, you can start using the IRMA phone app.

### <a name=sse></a>Personalizing the app
When you first start the IRMA phone app, it does not yet contain any attributes about yourself.
You first need to obtain a start set of attributes.
You can do this by choosing the menu option *Enroll*.
The app will then start a self enrolment procedure, where it will connect to our enrolment server.

After pressing "continue" your app will transmit your IMSI number, a unique number used by your mobile phone provider to identify you, to our server.
In the future this can be used by your mobile phone provider to ensure that the phone being used actually belongs to you.

You are then asked to fill in the document number, your date of birth and the date of expiry of your electronic identity document (currently we only support Dutch identity cards and passports).
This is required for your phone to be able to communicate with your identity document.

After pressing the continue button, the app will request you to hold your identity document against your phone and start reading your identity document.
The progress bar indicates the progress in reading your passport.
Our server will then verify if your identity document is genuine.
If this is the case, your IRMA phone app will receive several attributes deduced from your passport (e.g.\ your name and whether you are over 18).

You now see an overview of your attributes in the apps main view.

### Using the app 
The main purpose of the IRMA phone app is to allow you to do online authentications using some subset of your available attributes.
After [obtaining your attributes](#sse), you can use the app to [register] (TODO) for the upcoming IRMA meeting.
You can also play around with the IRMA functionality on the [IRMA demo website] (https://demo.irmacard.org).
All of these functions will require you to disclose some attributes to a verifier.
Let's look at how this works:


TODO: screen shots of how to use the IRMA phone app


Security implications
--------------------
IRMA is a privacy enhancing technology. The IRMA infrastructure will not leak any information about your identity to other parties, except that which you allow.
The IRMA team cares passionately about your privacy. However, since this is a first public functionality test there are some caveats.

### App security
This public test is purely a functionality test.
The current version of the app has seen no security hardening yet!
In practice this means that if an attacker gets his hands on your phone, he is likely to be able to obtain your attributes.
As we are currently only in a test system, no harm can come of using these attributes, and likely your phone will contain more privacy sensitive data the these attributes, but it is good to be aware of this.

### Android permissions
The IRMA phone app requires several permissions, which Android will ask you to approve upon installing the app.
We attempted to keep the number of permissions required to a minimum.
Here we explain why we require each permission:

* NFC, the IRMA phone app uses NFC to read your identity document during [self enrolment](#sse).
* INTERNET, Internet connection is needed, as both enrolment and verifications happen online.
* CAMERA, camera access is required to scan the [QR code](#QR_code) during authentication.
* VIBRATE, your phone will vibrate when a connection with you electronic identity document is established.
* READ PHONE STATE, allows the app to read your IMSI number, which will be an integral part of the enrolment procedure. This permission also allows the collection of some debugging information to be collected in case of a [crash](#crash).
* ACCESS NETWORK STATE, this permission allows the app to only send metrics data when there is a wifi connection.

### <a name=crasg></a>Crash reports
As the IRMA phone app is still under active development, things can go wrong. We have not yet tested this software on a wide variety of devices (that's what this public test is for).
When the app crashes, or something goes wrong internally, the app will send a *crash report* to our servers.
Although we have done our best to ensure the contents of your attributes will never be included in these crash reports, other privacy sensitive values might be included, such as the exact brand and type of your phone, the version of your mobile OS and the time of the crash.
We will treat this information as delicate, and remove it when we no longer need it.

Also, please be advised that these crash reports are transmitted even when you have no wifi connection.
This means that even though the crash reports are quite small (about the size of an average e-mail), their transmission can incur some mobile data costs.

### metrics
We also gather some statistics and metrics about the use of the IRMA phone app.
Data such as how long it takes your phone to do the necessary computations for proving your attributes.
These data are stored on your phone and transmitted to us periodically in batches, only over wifi connections.
We have taken care to make sure that no privacy sensitive details are revealed this way.


How do I get it?
----------------
### system requirements
To use the IRMA phone app, you will need an smart phone at least running Android 4.1 (Jelly bean) or higher.
Your phone will also need to be equipped with NFC and a camera, though these are very standard features of Android phones.
Furthermore, to obtain your initial attributes you will need a Dutch electronic identity card or passport.
We do not yet support the Dutch electronic driver's license or electronic documents from other countries.

### <a name=install></a>download and installation
Since this is a limited public functionality test, we have not yet made the app available in the Android app store.
In order to install the app you will have to enable the security option "unknown sources", which allows you to install apps from outside of the Android market.
Then simply use your phone to go to this URL:[TODO](TODO), to download and install the app.
Enjoy!

