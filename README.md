# DJI Android Mobile SDK Bridge App

## What is this?

The Android Bridge App allows efficient development of Android applications. 

Many DJI products require the Android device to be connected directly through USB to a remote controller, which means the Android device can only be connected to the PC through WiFi debugging. While workable, development is slow as resource intensive tasks such as profiling or transferring a new build to the mobile device can take a long time. In addition, the Android Studio emulator cannot be used to do any development.

The Android bridge app runs on an Android device connected to the remote controller and accepts a network connection from another Android device running an SDK based application. It acts as a bridge between the remote controller and the Android device running the SDK based application.

This means:

* The Android device running the SDK based application can connect with USB to the PC, while connecting over WiFi to the bridge app
* Or the Android Studio emulator can be used on the PC and connect to the bridge app over WiFi

This makes it easier develop, debug, setup CI environments, share devices in a team, or even do remote development with devices.

## Compatibility

The Bridge App is compatible with the Android DJI Mobile SDK v4.0 and above.

## Setup

When using the bridge app, two Android devices are used (or one device with the bridge app and the emulator):

1. An Android device with the BridgeApp apk that is connected directly to the remote controller
2. An Android device running an SDK based application

Both devices must be able to resolve each other's IP address - and are typically used on the same network.

The steps to make the bridge app work are:

* Device 1: 
  * Start the bridge app
  * Connect the Android device to remote controller
  * Grant USB access to the bridge app
  * Make sure the red light in the top left corner of the screen turns green - this shows the bridge app is connected with the DJI product
  * There will be an IP address in the middle of the screen - which is used with the second device.
* Device 2:
  * Device 2 should be running a DJI Mobile SDK based application.
  * The IP address of Device 1 should be passed to `enableBridgeModeWithBridgeAppIP` in `SDKManager` to connect with Device 1.
  * Make sure the red light in the top left corner of Device 2's screen turns green - this shows Device 2 is connected with Device 1.
  * The application can now be run remotely.

## Notes
This is a beta version of the Bridge App. Please provide feedback in areas you think it could be improved or is unstable.



