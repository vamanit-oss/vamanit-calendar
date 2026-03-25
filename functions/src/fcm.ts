/**
 * fcm.ts
 *
 * Sends a silent FCM data message to a device token.
 * The Android app receives it in VamanitFirebaseMessagingService.onMessageReceived()
 * and calls CalendarRepository.refresh() — no UI notification is shown.
 */

import { getMessaging } from "firebase-admin/messaging";
import { logger } from "firebase-functions/v2";

export type CalendarSource = "microsoft" | "google" | "all";

/**
 * Sends a calendar-refresh push to a specific device.
 *
 * @param fcmToken  FCM registration token of the target device.
 * @param source    Which calendar source changed ("microsoft" | "google" | "all").
 * @param eventType The type of change (optional — for future targeted invalidation).
 */
export async function sendCalendarRefreshPush(
  fcmToken:  string,
  source:    CalendarSource = "all",
  eventType: string        = "calendar_refresh"
): Promise<void> {
  const message = {
    token: fcmToken,
    // Data-only message (no "notification" key) — delivers silently,
    // works even when the app is in the background.
    data: {
      type:   eventType,
      source: source,
    },
    android: {
      // HIGH priority so the device wakes up immediately.
      priority: "high" as const,
    },
  };

  try {
    const messageId = await getMessaging().send(message);
    logger.info(`FCM sent to ${fcmToken.slice(0, 12)}… → ${messageId} (source=${source})`);
  } catch (err) {
    logger.error("FCM send failed", { fcmToken: fcmToken.slice(0, 12), err });
    throw err;
  }
}
