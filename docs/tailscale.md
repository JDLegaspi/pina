# Connect Pina to Rex over Tailscale

Pina talks to the Rex server on your Mac over HTTPS. Tailscale carries that connection, so your phone reaches the Mac from anywhere without opening a port to the internet. Rex checks who you are from your Tailscale identity. Pina sends no password or token.

You need:

- Rex on your Mac, with a [Tailscale](https://tailscale.com) account.
- An Android phone with Pina installed.

## 1. Turn on HTTPS for your tailnet

Rex serves its API with a Tailscale HTTPS certificate, so your tailnet needs MagicDNS and HTTPS certificates.

1. Open the [DNS page](https://login.tailscale.com/admin/dns) of the Tailscale admin console.
2. Turn on **MagicDNS** if it is off.
3. Under **HTTPS Certificates**, click **Enable HTTPS**.

## 2. Serve Rex over Tailscale

Rex runs its own small Tailscale node, separate from the Tailscale app on your Mac. It appears in your tailnet as its own device.

1. In Rex on your Mac, open **Settings** and find the Tailscale section.
2. Turn on **Serve over Tailscale**.
3. The first time, Rex asks you to sign in. Open the link and approve the device in your browser.
4. Keep **Limit access to your Tailscale account** on, unless other people in your tailnet should reach your terminals.

You can do the same from a terminal:

```sh
rex server tailscale on
rex server status   # shows "authenticate: https://login.tailscale.com/..." until you approve it
```

`rex server tailscale on` lasts only until the server restarts. The switch in Rex Settings keeps it on.

## 3. Find the server address

Rex shows its address once HTTPS is ready:

```sh
rex server tailscale pair --json
```

The address looks like `https://rex.<your-tailnet>.ts.net`. In the Rex app, **Pair Device with QR Code** shows the same address. The code holds only the address, not a credential.

## 4. Put your phone on the tailnet

Pick one:

- **Tailscale app (recommended).** Install [Tailscale](https://play.google.com/store/apps/details?id=com.tailscale.ipn) from Google Play. Sign in with the same account and connect. Pina then uses the phone's normal network.
- **Built-in Tailscale.** In Pina, open **Preferences → Network**, turn on **Built-in Tailscale**, and tap **Apply**. Then tap **Sign in** and finish in the browser. This adds a separate `pina-android` device to your tailnet, and the Tailscale app is not needed. Leave **Control server** blank unless you use Headscale.

## 5. Add the server in Pina

1. Open Pina and tap **Add server**. To add another server later, tap the gear, then **Servers**, then **Add server**.
2. Paste the address from step 3, such as `https://rex.<your-tailnet>.ts.net`.
3. Give it a name, such as `MacBook`, and tap **Save**.

Pina connects and lists your agent sessions. On Android 17 or later, allow local network access if Android asks.

## Troubleshooting

| Symptom | Cause and fix |
| --- | --- |
| `rex server status` shows `https: pending` | HTTPS certificates are off for the tailnet. Do step 1, then wait a minute. |
| `rex server status` shows `needs login` | The Rex node is not approved yet. Open the `authenticate:` link and approve it. |
| Pina reports HTTP 403 | The phone's Tailscale user is not allowed in. Sign the phone in with the same account as the Mac, or turn off **Limit access to your Tailscale account**. |
| Pina can't resolve `*.ts.net` | The phone is not on the tailnet, or MagicDNS is off on the phone. Open the Tailscale app and connect. |
| It worked, then stopped after a restart | The server was started without Tailscale. Use the switch in Rex Settings instead of `rex server tailscale on`. |
| Sessions appear but no status shows | Pina shows the status that programs report to Rex, the same status Rex's sidebar shows on the Mac. If the Mac sidebar shows none for a session, Pina shows none either. Start Claude Code inside a Rex tab. |

## SSH instead of HTTPS

Pina also connects with `ssh://user@host` when the host runs an SSH server that allows Unix socket forwarding. Tailscale SSH works with no key. See [connection options](connections.md) for SSH keys and Headscale.
