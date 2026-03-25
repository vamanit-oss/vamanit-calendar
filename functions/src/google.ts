/**
 * google.ts
 *
 * Handles Google Calendar push notifications.
 *
 * Google sends a POST to this endpoint whenever a watched calendar changes.
 * All change information is in the request headers — the body is empty.
 *
 * Spec: https://developers.google.com/calendar/api/guides/push
 *
 * Setup (done by Android app via PushSubscriptionManager):
 *   POST /calendars/{calendarId}/events/watch
 *   {
 *     "id": "<uuid>",           ← channelId stored in tokenStore
 *     "type": "web_hook",
 *     "address": "<this URL>",
 *     "token": "<fcmToken>",    ← we embed the FCM token as the channel token
 *     "expiration": <ms epoch>  ← max 1 week
 *   }
 *
 * Google will include the "token" value in X-Goog-Channel-Token on every
 * notification, so we don't even need a Firestore lookup for Google push.
 */

import { onRequest, HttpsOptions } from "firebase-functions/v2/https";
import { logger } from "firebase-functions/v2";
import {
  registerGoogleChannel,
  removeGoogleChannel,
  getFcmTokenForChannel,
} from "./tokenStore";
import { sendCalendarRefreshPush } from "./fcm";

const OPTS: HttpsOptions = { region: "us-central1", cors: false };

/**
 * POST /googleWebhook — Google Calendar push notification receiver.
 *
 * Relevant headers:
 *   X-Goog-Channel-Id       — the channelId we set up
 *   X-Goog-Channel-Token    — the FCM token we embedded at watch creation
 *   X-Goog-Resource-State   — "sync" (initial) | "exists" (change) | "not_exists" (deleted)
 *   X-Goog-Resource-Id      — opaque resource identifier
 */
export const googleWebhook = onRequest(OPTS, async (req, res) => {
  if (req.method !== "POST") { res.status(405).send("Method Not Allowed"); return; }

  const channelId     = req.headers["x-goog-channel-id"]    as string | undefined;
  const channelToken  = req.headers["x-goog-channel-token"] as string | undefined;
  const resourceState = req.headers["x-goog-resource-state"] as string | undefined;

  // Acknowledge immediately — Google marks the channel as failed after timeout.
  res.status(200).send("OK");

  // "sync" is Google's initial handshake — not a real change.
  if (resourceState === "sync") {
    logger.info(`Google: sync ping for channel ${channelId}`);
    return;
  }

  if (!channelId) { logger.warn("Google: missing X-Goog-Channel-Id"); return; }

  // FCM token: prefer the embedded channel token (fast path), fall back to Firestore.
  const fcmToken = channelToken ?? await getFcmTokenForChannel(channelId);
  if (!fcmToken) {
    logger.warn(`Google: no FCM token for channel ${channelId}`);
    return;
  }

  logger.info(`Google: calendar change (state=${resourceState}) → pushing FCM`);
  await sendCalendarRefreshPush(fcmToken, "google", "calendar_event_changed").catch(
    (err) => logger.error("Google: FCM push failed", err)
  );
});

/**
 * POST /registerGoogleChannel
 *
 * Called by the Android app after it sets up a Google Calendar watch.
 * Body: { channelId: string, calendarId: string, fcmToken: string }
 */
export const registerGoogleChannel_ = onRequest(
  { ...OPTS, name: "registerGoogleChannel" } as HttpsOptions,
  async (req, res) => {
    if (req.method !== "POST") { res.status(405).send("Method Not Allowed"); return; }

    const { channelId, calendarId, fcmToken } =
      req.body as { channelId?: string; calendarId?: string; fcmToken?: string };

    if (!channelId || !calendarId || !fcmToken) {
      res.status(400).json({ error: "channelId, calendarId and fcmToken are required" });
      return;
    }

    await registerGoogleChannel(channelId, calendarId, fcmToken);
    logger.info(`Registered Google channel ${channelId} (cal=${calendarId})`);
    res.status(200).json({ ok: true });
  }
);

/** POST /unregisterGoogleChannel — Body: { channelId: string } */
export const unregisterGoogleChannel = onRequest(OPTS, async (req, res) => {
  if (req.method !== "POST") { res.status(405).send("Method Not Allowed"); return; }
  const { channelId } = req.body as { channelId?: string };
  if (!channelId) { res.status(400).json({ error: "channelId required" }); return; }
  await removeGoogleChannel(channelId);
  res.status(200).json({ ok: true });
});
