/**
 * Vamanit Calendar — Firebase Cloud Functions
 *
 * Webhook bridge between Microsoft Graph / Google Calendar change notifications
 * and FCM push messages delivered to the Android app.
 *
 * Deploy:
 *   cd functions && npm run deploy
 *
 * Endpoints after deploy (replace <project> with your Firebase project ID):
 *   Microsoft webhook:
 *     https://us-central1-<project>.cloudfunctions.net/microsoftWebhook
 *   Google webhook:
 *     https://us-central1-<project>.cloudfunctions.net/googleWebhook
 *   Registration:
 *     https://us-central1-<project>.cloudfunctions.net/registerMicrosoftSubscription
 *     https://us-central1-<project>.cloudfunctions.net/registerGoogleChannel
 *
 * Set BACKEND_WEBHOOK_BASE_URL in Android local.properties to the base URL above.
 */

import { initializeApp } from "firebase-admin/app";
initializeApp();

// Microsoft Graph change notifications
export { microsoftWebhook, registerMicrosoftSubscription, unregisterMicrosoftSubscription }
  from "./microsoft";

// Google Calendar push notifications
export { googleWebhook, registerGoogleChannel_ as registerGoogleChannel, unregisterGoogleChannel }
  from "./google";
