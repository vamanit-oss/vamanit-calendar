/**
 * tokenStore.ts
 *
 * Firestore-backed registry: maps a Graph subscription ID or Google
 * channel ID → the FCM device token that should receive pushes.
 *
 * Collection layout:
 *   subscriptions/{subscriptionId}  { fcmToken, provider, createdAt }
 *   googleChannels/{channelId}      { fcmToken, calendarId, createdAt }
 */

import { getFirestore, Timestamp } from "firebase-admin/firestore";

const db = () => getFirestore();

// ── Microsoft Graph subscriptions ─────────────────────────────────────────────

export interface SubscriptionEntry {
  fcmToken:     string;
  provider:     "microsoft";
  createdAt:    Timestamp;
}

/** Registers a Graph subscriptionId → FCM token mapping. */
export async function registerSubscription(
  subscriptionId: string,
  fcmToken:       string
): Promise<void> {
  await db()
    .collection("subscriptions")
    .doc(subscriptionId)
    .set({
      fcmToken,
      provider:  "microsoft",
      createdAt: Timestamp.now(),
    } satisfies SubscriptionEntry);
}

/** Returns the FCM token registered for [subscriptionId], or null. */
export async function getFcmTokenForSubscription(
  subscriptionId: string
): Promise<string | null> {
  const snap = await db().collection("subscriptions").doc(subscriptionId).get();
  return snap.exists ? (snap.data() as SubscriptionEntry).fcmToken : null;
}

/** Removes the subscription entry (call on unsubscribe / logout). */
export async function removeSubscription(subscriptionId: string): Promise<void> {
  await db().collection("subscriptions").doc(subscriptionId).delete();
}

// ── Google Calendar channels ───────────────────────────────────────────────────

export interface ChannelEntry {
  fcmToken:   string;
  calendarId: string;
  createdAt:  Timestamp;
}

/** Registers a Google Calendar channelId → FCM token mapping. */
export async function registerGoogleChannel(
  channelId:  string,
  calendarId: string,
  fcmToken:   string
): Promise<void> {
  await db()
    .collection("googleChannels")
    .doc(channelId)
    .set({
      fcmToken,
      calendarId,
      createdAt: Timestamp.now(),
    } satisfies ChannelEntry);
}

/** Returns the FCM token registered for [channelId], or null. */
export async function getFcmTokenForChannel(
  channelId: string
): Promise<string | null> {
  const snap = await db().collection("googleChannels").doc(channelId).get();
  return snap.exists ? (snap.data() as ChannelEntry).fcmToken : null;
}

/** Removes the channel entry. */
export async function removeGoogleChannel(channelId: string): Promise<void> {
  await db().collection("googleChannels").doc(channelId).delete();
}
