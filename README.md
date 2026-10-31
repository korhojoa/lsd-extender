# LSD extender

LSD extender adds independent pages and features to a MIB2 High head unit.
The core loads feature archives. The wireless Android Auto feature reads
phone state and controls the virtual USB device. The settings feature adds
owned pages beside the stock interface.

## Build

Install Python 3 and Podman. Fetch the pinned Java compiler image, then build:

```sh
podman pull docker.io/library/eclipse-temurin@sha256:32733e308c91c4cde5f003e37c3def82bb931e0242bf4062cfa45eefe3814df2
./build.sh
```

Run the commands from this checkout. The build script can also be called by
its full path from another folder. Output archives are in `build/card`.
Remove the generated `build` folder before a clean build. Set
`CONTAINER_ENGINE=docker` to use Docker instead of Podman.

The `compile-standins` folder contains our compile-only Java declarations.
Each declaration supplies only type and member signatures. Method bodies
throw an exception. These declarations contain no stock implementation.
The build packages only the `org.korho.lsdextender` classes. It refuses
compile declarations, other classes, duplicate members and newer class formats.
Do not install compile declarations or their class files.

The component versions are in `parts.json`. Generated `Build` classes keep
the component release labels. The compile declarations do not supply stock
field values.

## Installation

Do not install the Java archives alone. Wireless Android Auto also requires
the Bluetooth library, credential publisher, virtual USB driver and installer.
The release archive must supply these parts together. The scripts in
`bootstrap/native-aa-kit` install and remove our Bluetooth wrapper through
the stock `/eso/bin/PhoneCustomer/btstack` path. Keep the complete kit together.
The installer refuses to replace an existing file in that path. To replace a
wrapper, run `remove.sh` first.

The released configuration is in `bootstrap/lsd-extender.conf`. Each setting
shows its value and a short explanation. The bundle's `prepare-install.py` rewrites
the paths for the chosen installation folder.

## Scripts

- `mount-vaa.sh` mounts or removes the virtual USB driver. It uses a RAM copy
  so a card update can replace the driver file.
- `native-aa-kit/install.sh` installs the customer wrapper.
- `native-aa-kit/remove.sh` removes only that exact wrapper.

## Status

Wireless Android Auto and Bluetooth calls worked together on a MIB2 High
unit. Other firmware and hardware need their own test. The build packages
only our classes and scripts. It includes no firmware or stock resources.

## Terms

| Term | Meaning |
| --- | --- |
| HMI | Human-machine interface: the head unit display and controls. |
| DSI | The stock service interface used for device state and control. |
| PPS | QNX publish/subscribe objects used for shared state. |
| QNX | The operating system used by the head unit. |
| `compile-standins` | The folder of type and member declarations used only by the compiler. |

Project source uses GPL-3.0-only. See `COPYING`.

## Optional code

The source includes optional update, game, input and diagnostic features.
The release configuration disables these paths. Some require programs that
are not published or included in this bundle. Do not enable them without
their required programs and a separate unit test. The TCP command listener
can start programs and reload features; port 0 disables it. The wireless
firewall rule opens TCP 5288 only on the access point interface `uap0`.

The build produces every source feature. The combined bundle selects only
the seven feature archives named in its configuration. The remaining
archives stay available for separate builds. The source and expected hashes
in `parts.json` identify the tested inputs; the Java compiler does not use
those fields to replace source or to set runtime behavior.

The core's generated `Build.VERSION` is a placeholder. `RuntimeCore` reads
`core-build.properties` and checks its source identity before it reports
the actual core version. Keep that file beside the core archive.

The compile declarations were written for this project from interface names
and signatures. They contain no vendor implementation and are not installed.
The workflow uses the public Rust compiler image and downloads the same
pinned Java compiler used by the local Java image.
