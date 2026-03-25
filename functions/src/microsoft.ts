/**
 * microsoft.ts
 *
 * Handles Microsoft Graph Change Notifications.
 *
 * Graph sends a POST to this endpoint whenever a calendar event is
 * created, updated, or deleted for a subscribed user.
 *
 * Spec: https://learn.microsoft.com/en-us/graph/change-notifications-delivery-webhooks
 *
 * Two phases:
 * 1. Validation handshake — Graph sends ?validationToken=... and expects
 *    it echoed back as plain text with 200 OK (within 10 s).
 * 2. Normal notification — JSON body with array of change notifications.
 *    Must respond 202 Accepted quickly; heavy work done asynchronously.
 */

import {
  onRequest,
  HttpsOptions,
} from "firebase-functions/v2/https";
import { logger } from "firebase-functions/v2";
import { getFcmTokenForSubscription, registerSubscription, removeSubscription } from "./tokenStore";
import { sendCalendarRefreshPush } from "./fcm";

const OPTS: HttpsOptions = { region: "us-central1", cors: false };

/** POST /microsoftWebhook — Graph change notification receiver. */
export const microsoftWebhook = onRequest(OPTS, async (req, res) => {
  // ── 1. Validation handshake ─────────────────────────────────────────────────
  if (req.method === "GET" || req.query["validationToken"]) {
    const token = req.query["validationToken"] as string;
    logger.info("Graph validation handshake received");
    res.set("Content-Type", "text/plain");
    res.status(200).send(token);
    return;
  }

  if (req.method !== "POST") {
    res.status(405).send("Method Not Allowed");
    return;
  }

  // ── 2. Notification payload ─────────────────────────────────────────────────
  // Respond 202 immediately — Graph retries if it doesn't get a fast ack.
  res.status(202).send("Accepted");

  const body = req.body as GraphNotificationBody;
  if (!body?.value?.length) return;

  logger.info(`Graph: ${body.value.length} notification(s) received`);

  await Promise.allSettled(
    body.value.map(async (notification) => {
      const subId = notification.subscriptionId;
      if (!subId) return;

      const fcmToken = await getFcmTokenForSubscription(subId);
      if (!fcmToken) {
        logger.warn(`Graph: no FCM token for subscriptionId ${subId}`);
        return;
      }

      await sendCalendarRefreshPush(fcmToken, "microsoft", notification.changeType ?? "calendar_refresh");
    })
  );
});

/**
 * POST /registerMicrosoftSubscription
 *
 * Called by the Android app after it successfully creates a Graph subscription.
 * Body: { subscriptionId: string, fcmToken: string }
 */
export const registerMicrosoftSubscription = onRequest(OPTS, async (req, res) => {
  if (req.method !== "POST") { res.status(405).send("Method Not Allowed"); return; }

  const { subscriptionId, fcmToken } = req.body as { subscriptionId?: string; fcmToken?: string };
  if (!subscriptionId || !fcmToken) {
    res.status(400).json({ error: "subscriptionId and fcmToken are required" });
    return;
  }

  await registerSubscription(subscriptionId, fcmToken);
  logger.info(`Registered subscription ${subscriptionId} → ${fcmToken.slice(0, 12)}…`);
  res.status(200).json({ ok: true });
});

/**
 * POST /unregisterMicrosoftSubscription
 * Body: { subscriptionId: string }
 */
export const unregisterMicrosoftSubscription = onRequest(OPTS, async (req, res) => {
  if (req.method !== "POST") { res.status(405).send("Method Not Allowed"); return; }

  const { subscriptionId } = req.body as { subscriptionId?: string };
  if (!subscriptionId) { res.status(400).json({ error: "subscriptionId required" }); return; }

  await removeSubscription(subscriptionId);
  res.status(200).json({ ok: true });
});

// ── Types ─────────────────────────────────────────────────────────────────────

interface GraphNotification {
  subscriptionId:              string;
  changeType?:                 string;
  resource?:                   string;
  clientState?:                string;
  subscriptionExpirationDateTime?: string;
}

interface GraphNotificationBody {
  value: GraphNotification[];
}
