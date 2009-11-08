package net.everythingandroid.smspopup;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningTaskInfo;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.preference.PreferenceManager;
import android.provider.Contacts;
import android.provider.Contacts.PeopleColumns;
import android.provider.Contacts.Photos;
import android.telephony.PhoneNumberUtils;
import android.telephony.gsm.SmsMessage;
import android.text.TextUtils;

public class SmsPopupUtils {
  //Content URIs for SMS app, these may change in future SDK
  public static final Uri MMS_SMS_CONTENT_URI = Uri.parse("content://mms-sms/");
  public static final Uri THREAD_ID_CONTENT_URI =
    Uri.withAppendedPath(MMS_SMS_CONTENT_URI, "threadID");
  public static final Uri CONVERSATION_CONTENT_URI =
    Uri.withAppendedPath(MMS_SMS_CONTENT_URI, "conversations");
  public static final String SMSTO_URI = "smsto:";
  private static final String UNREAD_CONDITION = "read=0";

  public static final Uri SMS_CONTENT_URI = Uri.parse("content://sms");
  public static final Uri SMS_INBOX_CONTENT_URI = Uri.withAppendedPath(SMS_CONTENT_URI, "inbox");

  public static final Uri MMS_CONTENT_URI = Uri.parse("content://mms");
  public static final Uri MMS_INBOX_CONTENT_URI = Uri.withAppendedPath(MMS_CONTENT_URI, "inbox");

  public static final String SMSMMS_ID = "_id";
  public static final String SMS_MIME_TYPE = "vnd.android-dir/mms-sms";
  public static final int READ_THREAD = 1;
  public static final int MESSAGE_TYPE_SMS = 1;
  public static final int MESSAGE_TYPE_MMS = 2;

  public static final int CONTACT_PHOTO_PLACEHOLDER = android.R.drawable.ic_dialog_info;

  // The size of the contact photo thumbnail on the popup
  public static final int CONTACT_PHOTO_THUMBSIZE = 96;

  // The max size of either the width or height of the contact photo
  public static final int CONTACT_PHOTO_MAXSIZE = 1024;

  private static final String[] AUTHOR_CONTACT_INFO = { "Adam K <smspopup@everythingandroid.net>" };
  private static final String[] AUTHOR_CONTACT_INFO_DONATE = { "Adam K <adam@everythingandroid.net>" };

  public static final Uri DONATE_PAYPAL_URI =
    Uri.parse("https://www.paypal.com/cgi-bin/webscr?cmd=_s-xclick&hosted_button_id=8246419");
  public static final Uri DONATE_MARKET_URI =
    Uri.parse("market://search?q=pname:net.everythingandroid.smspopupdonate");

  // TODO: remove once Cupcake support is no longer needed
  private static final int ECLAIR_SDK_VERSION = 5;

  /**
   * Looks up a contacts display name by contact id - if not found, the address
   * (phone number) will be formatted and returned instead.
   */
  public static String getPersonName(Context context, String id, String address) {

    // Check for id, if null return the formatting phone number as the name
    if (id == null) {
      if (address != null) {
        return PhoneNumberUtils.formatNumber(address);
      } else {
        return null;
      }
    }

    Cursor cursor = context.getContentResolver().query(
        Uri.withAppendedPath(Contacts.People.CONTENT_URI, id),
        new String[] { PeopleColumns.DISPLAY_NAME }, null, null, null);

    if (cursor != null) {
      try {
        if (cursor.getCount() > 0) {
          cursor.moveToFirst();
          String name = cursor.getString(0);
          if (Log.DEBUG) Log.v("Contact Display Name: " + name);
          return name;
        }
      } finally {
        cursor.close();
      }
    }

    // If we're pre-Eclair then the contact couldn't be found
    if (getSDKVersionNumber() < ECLAIR_SDK_VERSION) {
      if (address != null) {
        return PhoneNumberUtils.formatNumber(address);
      }
      return null;
    }

    /*
     * Lookup the contact display name via the Eclair URI:
     * ContactsContract.PhoneLookup.CONTENT_FILTER_URI;
     * 
     * TODO: Shift away from using hard-coded URIs (will only be able to do this once
     * pre-eclair support is no longer needed OR Market supports multiple apps per package so
     * I can compile a different one for eclair.
     */
    Uri newPhoneLookupUri =
      Uri.withAppendedPath(Uri.parse("content://com.android.contacts/phone_lookup"), address);
    String[] newProjection = new String[] { "display_name" };

    cursor = context.getContentResolver().query(
        newPhoneLookupUri,
        newProjection, null, null, null);

    if (cursor != null) {
      try {
        if (cursor.getCount() > 0) {
          cursor.moveToFirst();
          String name = cursor.getString(0);
          if (Log.DEBUG) Log.v("Found contact name (using Eclair+ URI): " + name);
          return name;
        }
      } finally {
        cursor.close();
      }
    }

    if (address != null) {
      return PhoneNumberUtils.formatNumber(address);
    }

    return null;
  }

  /**
   * Looks up a contacts id, given their address (phone number in this case).
   * Returns null if not found
   */
  public static String getPersonIdFromPhoneNumber(Context context, String address) {
    if (address == null) return null;

    Cursor cursor = context.getContentResolver().query(
        Uri.withAppendedPath(Contacts.Phones.CONTENT_FILTER_URL, address),
        new String[] { Contacts.Phones.PERSON_ID }, null, null, null);

    if (cursor != null) {
      try {
        if (cursor.getCount() > 0) {
          cursor.moveToFirst();
          Long id = Long.valueOf(cursor.getLong(0));
          if (Log.DEBUG) Log.v("Found person: " + id);
          return (String.valueOf(id));
        }
      } finally {
        cursor.close();
      }
    }

    // If we're pre-Eclair then the contact couldn't be found
    if (getSDKVersionNumber() < ECLAIR_SDK_VERSION) {
      return null;
    }

    /*
     * Lookup the contact id via the Eclair URI:
     * ContactsContract.PhoneLookup.CONTENT_FILTER_URI;
     * 
     * TODO: Shift away from using hard-coded URIs (will only be able to do this once
     * pre-eclair support is no longer needed OR Market supports multiple apps per package so
     * I can compile a different one for eclair.
     */
    Uri newPhoneLookupUri =
      Uri.withAppendedPath(Uri.parse("content://com.android.contacts/phone_lookup"), address);
    String[] newProjection = new String[] { "_id" };

    cursor = context.getContentResolver().query(
        newPhoneLookupUri, newProjection, null, null, null);

    if (cursor != null) {
      try {
        if (cursor.getCount() > 0) {
          cursor.moveToFirst();
          Long id = Long.valueOf(cursor.getLong(0));
          if (Log.DEBUG) Log.v("Found contact id (using Eclair+ URI): " + id);
          return (String.valueOf(id));
        }
      } finally {
        cursor.close();
      }
    }

    return null;
  }

  /**
   * Looks up a contacts id, given their email address.
   * Returns null if not found
   */
  public static String getPersonIdFromEmail(Context context, String address) {
    if (address == null)
      return null;

    final Uri WITH_EMAIL_OR_IM_FILTER_URI =
      Uri.parse("content://contacts/people/with_email_or_im_filter");

    Cursor cursor = context.getContentResolver().query(
        Uri.withAppendedPath(WITH_EMAIL_OR_IM_FILTER_URI, Uri.encode(address)),
        new String[] { SMSMMS_ID }, null, null, null);

    if (cursor != null) {
      try {
        if (cursor.getCount() > 0) {
          cursor.moveToFirst();
          Long id = Long.valueOf(cursor.getLong(0));
          if (Log.DEBUG) Log.v("Found person (by email): " + id);
          return (String.valueOf(id));
        }
      } finally {
        cursor.close();
      }
    }
    return null;
  }

  /**
   * 
   * Looks up a contats photo by their contact id, returns a Bitmap array
   * that represents their photo (or null if not found or there was an error.
   * 
   * I do my own scaling and validation of sizes - Android OS supports any size
   * for contact photos and some apps are adding huge photos to contacts.  Doing
   * the scaling myself allows me more control over how things play out in those
   * cases.
   * 
   * @param context
   * @param id contact id
   * @return Bitmap of the contacts photo (null if none or an error)
   */
  public static Bitmap getPersonPhoto(Context context, String id) {

    if (id == null) return null;
    if ("0".equals(id)) return null;

    // First let's just check the dimensions of the contact photo
    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inJustDecodeBounds = true;

    // The height and width are stored in 'options' but the photo itself is not loaded
    //    Contacts.People.loadContactPhoto(
    //        context, Uri.withAppendedPath(Contacts.People.CONTENT_URI, id), 0, options);
    loadContactPhoto(context, id, 0, options);

    // Raw height and width of contact photo
    int height = options.outHeight;
    int width = options.outWidth;

    if (Log.DEBUG) Log.v("Contact photo size = " + height + "x" + width);

    // If photo is too large or not found get out
    if (height > CONTACT_PHOTO_MAXSIZE || width > CONTACT_PHOTO_MAXSIZE  ||
        width == 0 || height == 0) return null;

    // This time we're going to do it for real
    options.inJustDecodeBounds = false;

    // Calculate new dimensions based on screen density
    final float scale = context.getResources().getDisplayMetrics().density;
    int thumbsize = CONTACT_PHOTO_THUMBSIZE;
    if (scale != 1.0) {
      if (Log.DEBUG) Log.v("Screen density is not 1.0, adjusting contact photo");
      thumbsize = Math.round(thumbsize * scale);
    }

    // If we have an abnormal photo size then sample it down
    if (height > thumbsize || width > thumbsize) {
      if (height < width) {
        options.inSampleSize = Math.round(height / thumbsize);
        // if (Log.DEBUG) Log.v("Contact photo inSampleSize = " + Math.round(height / CONTACT_PHOTO_THUMBSIZE));
      } else {
        options.inSampleSize = Math.round(width / thumbsize);
        // if (Log.DEBUG) Log.v("Contact photo inSampleSize = " + Math.round(height / CONTACT_PHOTO_THUMBSIZE));
      }
    }

    // Fetch the real contact photo (sampled down if needed)
    Bitmap contactBitmap = null;
    try {
      //      contactBitmap = Contacts.People.loadContactPhoto(
      //          context, Uri.withAppendedPath(Contacts.People.CONTENT_URI, id), 0, options);
      contactBitmap = loadContactPhoto(context, id, 0, options);
    } catch (OutOfMemoryError e) {
      Log.e("Out of memory when loading contact photo");
    }

    // Not found or error, get out
    if (contactBitmap == null) return null;

    // Calculate new dimensions based on screen density
    int newHeight = thumbsize;
    int newWidth = thumbsize;

    if (scale != 1.0) {
      if (Log.DEBUG) Log.v("Screen density is not 1.0, adjusting contact photo");
      newHeight = Math.round(thumbsize * scale);
      newWidth = Math.round(thumbsize * scale);
    }

    if (height != thumbsize || width != thumbsize) {
      if (height > width) {
        newWidth = Math.round(thumbsize * width / height);
      } else if (height < width) {
        newHeight = Math.round(thumbsize * height / width);
      }
    }

    // Return bitmap scaled to new height and width
    return Bitmap.createScaledBitmap(contactBitmap, newWidth, newHeight, true);
  }

  /**
   * Opens an InputStream for the person's photo and returns the photo as a Bitmap.
   * If the person's photo isn't present returns the placeholderImageResource instead.
   * @param context the Context
   * @param id the id of the person
   * @param placeholderImageResource the image resource to use if the person doesn't
   *   have a photo
   * @param options the decoding options, can be set to null
   */
  public static Bitmap loadContactPhoto(Context context, String id,
      int placeholderImageResource, BitmapFactory.Options options) {
    if (id == null) {
      return loadPlaceholderPhoto(placeholderImageResource, context, options);
    }

    InputStream stream = openContactPhotoInputStream(context.getContentResolver(), id);
    Bitmap bm = stream != null ? BitmapFactory.decodeStream(stream, null, options) : null;
    if (bm == null) {
      bm = loadPlaceholderPhoto(placeholderImageResource, context, options);
    }
    return bm;
  }

  private static Bitmap loadPlaceholderPhoto(int placeholderImageResource, Context context,
      BitmapFactory.Options options) {
    if (placeholderImageResource == 0) {
      return null;
    }
    return BitmapFactory.decodeResource(context.getResources(),
        placeholderImageResource, options);
  }

  /**
   * Opens an InputStream for the person's photo and returns the photo as a Bitmap.
   * If the person's photo isn't present returns the placeholderImageResource instead.
   * @param id the id of the person
   */
  public static InputStream openContactPhotoInputStream(ContentResolver cr, String id) {
    if (id == null) return null;
    if ("0".equals(id)) return null;

    Cursor cursor;
    Uri photoUri;

    // If we're pre-Eclair then lookup the contact using the standard URIs
    if (getSDKVersionNumber() < ECLAIR_SDK_VERSION) {

      /*
       * Contacts.People.CONTENT_URI is "content://contacts/people"
       * Contacts.Photos.CONTENT_DIRECTORY is "photo";
       * Uri will end up being "content://contacts/people/#contactId/photo"
       */
      if (Log.DEBUG) Log.v("openContactPhotoInputStream(): looking in Contacts.People");
      photoUri = Uri.withAppendedPath(
          Uri.withAppendedPath(Contacts.People.CONTENT_URI, id),
          Contacts.Photos.CONTENT_DIRECTORY);

      cursor = cr.query(photoUri, new String[] {Photos.DATA}, null, null, null);

      if (cursor != null) {
        try {
          if (cursor.moveToFirst()) {
            byte[] data = cursor.getBlob(0);
            if (data != null) {
              if (Log.DEBUG) Log.v("openContactPhotoInputStream(): contact photo found");
              return new ByteArrayInputStream(data);
            }
          }
        } finally {
          cursor.close();
        }
      }

      if (Log.DEBUG) Log.v("openContactPhotoInputStream(): looking in Contacts.Photos");
      /*
       * Contacts.Photos.CONTENT_URI is "content://contacts/photos"
       * Uri will end up being "content://contacts/photos/#contactId"
       */
      photoUri = Uri.withAppendedPath(Contacts.Photos.CONTENT_URI, id);

      cursor = cr.query(photoUri, new String[] {Photos.DATA}, null, null, null);

      if (cursor != null) {
        try {
          if (cursor.moveToFirst()) {
            byte[] data = cursor.getBlob(0);
            if (data != null) {
              if (Log.DEBUG) Log.v("openContactPhotoInputStream(): contact photo found");
              return new ByteArrayInputStream(data);
            }
          }
        } finally {
          cursor.close();
        }
      }

      return null;
    }

    /*
     * Lookup the contact photo via the Eclair URI:
     * ContactsContract.Contacts.CONTENT_URI + contact id +
     * ContactsContract.Contacts.Photo.CONTENT_DIRECTORY
     * 
     * The final result should look like:
     * "content://com.android.contacts/contacts/5/photo"
     * 
     * TODO: Shift away from using hard-coded URIs (will only be able to do this once
     * pre-eclair support is no longer needed OR Market supports multiple apps per package so
     * I can compile a different one for eclair.
     * 
     */

    if (Log.DEBUG) Log.v("openContactPhotoInputStream(): looking up photo via Eclair URI");
    Uri.Builder builder = Uri.parse("content://com.android.contacts/contacts").buildUpon();
    builder.appendEncodedPath(id);
    builder.appendEncodedPath("photo");
    photoUri = builder.build();
    String[] projection = new String[] { "data15" }; //ContactsContract.CommonDataKinds.Photo.PHOTO

    cursor = cr.query(photoUri, projection, null, null, null);

    if (cursor != null) {
      try {
        if (cursor.moveToFirst()) {
          byte[] data = cursor.getBlob(0);
          if (data != null) {
            if (Log.DEBUG) Log.v("openContactPhotoInputStream(): contact photo found");
            return new ByteArrayInputStream(data);
          }
        }
      } finally {
        cursor.close();
      }
    }

    return null;
  }

  /**
   * 
   * Tries to locate the message thread id given the address (phone or email)
   * of the message sender.
   * 
   * @param context a context to use
   * @param address phone number or email address of sender
   * @return the thread id (or 0 if there was a problem)
   */
  public static long findThreadIdFromAddress(Context context, String address) {
    if (address == null) return 0;

    String THREAD_RECIPIENT_QUERY = "recipient";

    Uri.Builder uriBuilder = THREAD_ID_CONTENT_URI.buildUpon();
    uriBuilder.appendQueryParameter(THREAD_RECIPIENT_QUERY, address);

    long threadId = 0;

    Cursor cursor = context.getContentResolver().query(
        uriBuilder.build(),
        new String[] { SMSMMS_ID },
        null, null, null);

    if (cursor != null) {
      try {
        if (cursor.moveToFirst()) {
          threadId = cursor.getLong(0);
        }
      } finally {
        cursor.close();
      }
    }
    return threadId;
  }

  /**
   * Marks a specific message as read
   */
  public static void setMessageRead(Context context, long messageId, int messageType) {

    SharedPreferences myPrefs = PreferenceManager.getDefaultSharedPreferences(context);
    boolean markRead = myPrefs.getBoolean(
        context.getString(R.string.pref_markread_key),
        Boolean.valueOf(context.getString(R.string.pref_markread_default)));
    if (!markRead) return;

    if (messageId > 0) {
      ContentValues values = new ContentValues(1);
      values.put("read", READ_THREAD);

      Uri messageUri;

      if (SmsMmsMessage.MESSAGE_TYPE_MMS == messageType) {
        // Used to use URI of MMS_CONTENT_URI and it wasn't working, not sure why
        // this is diff to SMS
        messageUri = Uri.withAppendedPath(MMS_INBOX_CONTENT_URI, String.valueOf(messageId));
      } else if (SmsMmsMessage.MESSAGE_TYPE_SMS == messageType) {
        messageUri = Uri.withAppendedPath(SMS_CONTENT_URI, String.valueOf(messageId));
      } else {
        return;
      }

      // Log.v("messageUri for marking message read: " + messageUri.toString());

      ContentResolver cr = context.getContentResolver();
      int result;
      try {
        result = cr.update(messageUri, values, null, null);
      } catch (Exception e) {
        result = 0;
      }
      if (Log.DEBUG) Log.v(String.format("message id = %s marked as read, result = %s", messageId, result ));
    }
  }

  /**
   * Marks a specific message thread as read - all messages in the thread will
   * be marked read
   */
  public static void setThreadRead(Context context, long threadId) {
    SharedPreferences myPrefs = PreferenceManager.getDefaultSharedPreferences(context);
    boolean markRead = myPrefs.getBoolean(
        context.getString(R.string.pref_markread_key),
        Boolean.valueOf(context.getString(R.string.pref_markread_default)));

    if (!markRead) return;

    if (threadId > 0) {
      ContentValues values = new ContentValues(1);
      values.put("read", READ_THREAD);

      ContentResolver cr = context.getContentResolver();
      int result = 0;
      try {
        result = cr.update(
            ContentUris.withAppendedId(CONVERSATION_CONTENT_URI, threadId),
            values, null, null);
      } catch (Exception e) {
        if (Log.DEBUG) Log.v("error marking thread read");
      }
      if (Log.DEBUG) Log.v("thread id " + threadId + " marked as read, result = " + result);
    }
  }

  /**
   * Tries to locate the message id (from the system database), given the message
   * thread id, the timestamp of the message and the type of message (sms/mms)
   */
  public static long findMessageId(Context context, long threadId, long timestamp,
      String body, int messageType) {

    long id = 0;
    String selection = "body = " + DatabaseUtils.sqlEscapeString(body);
    final String sortOrder = "date DESC";
    final String[] projection = new String[] { "_id", "date", "thread_id", "body" };

    if (threadId > 0) {
      if (Log.DEBUG) Log.v("Trying to find message ID");
      if (SmsMmsMessage.MESSAGE_TYPE_MMS == messageType) {
        // It seems MMS timestamps are stored in a seconds, whereas SMS timestamps are in millis
        selection += " and date = " + (timestamp / 1000);
      }

      // Log.v("Where is: " + where);
      // Log.v("ThreadId is: " + threadId);

      Cursor cursor = context.getContentResolver().query(
          ContentUris.withAppendedId(CONVERSATION_CONTENT_URI, threadId),
          projection,
          selection,
          null,
          sortOrder);

      if (cursor != null) {
        try {
          if (cursor.moveToFirst()) {
            id = cursor.getLong(0);
            if (Log.DEBUG) Log.v("Message id found = " + id);
            //Log.v("Timestamp = " + cursor.getLong(1));
          }
        } finally {
          cursor.close();
        }
      }
    }
    return id;
  }

  /**
   * Tries to delete a message from the system database, given the thread id,
   * the timestamp of the message and the message type (sms/mms).
   */
  public static void deleteMessage(Context context, long messageId, long threadId, int messageType) {

    if (messageId > 0) {
      if (Log.DEBUG) Log.v("id of message to delete is " + messageId);
      Uri deleteUri;

      if (SmsMmsMessage.MESSAGE_TYPE_MMS == messageType) {
        deleteUri = Uri.withAppendedPath(MMS_CONTENT_URI, String.valueOf(messageId));
      } else if (SmsMmsMessage.MESSAGE_TYPE_SMS == messageType) {
        deleteUri = Uri.withAppendedPath(SMS_CONTENT_URI, String.valueOf(messageId));
      } else {
        return;
      }
      int count = context.getContentResolver().delete(deleteUri, null, null);
      if (Log.DEBUG) Log.v("Messages deleted: " + count);
      if (count == 1) {
        //TODO: should only set the thread read if there are no more unread
        // messages
        setThreadRead(context, threadId);
      }
    }
  }

  /**
   * 
   */
  public static Intent getSmsIntent() {
    Intent conversations = new Intent(Intent.ACTION_MAIN);
    //conversations.addCategory(Intent.CATEGORY_DEFAULT);
    conversations.setType(SMS_MIME_TYPE);
    // should I be using FLAG_ACTIVITY_RESET_TASK_IF_NEEDED??
    int flags =
      Intent.FLAG_ACTIVITY_NEW_TASK |
      Intent.FLAG_ACTIVITY_SINGLE_TOP |
      Intent.FLAG_ACTIVITY_CLEAR_TOP;
    conversations.setFlags(flags);

    return conversations;
  }

  /**
   * 
   */
  public static Intent getSmsToIntentFromThreadId(Context context, long threadId) {
    Intent popup = new Intent(Intent.ACTION_VIEW);
    // should I be using FLAG_ACTIVITY_RESET_TASK_IF_NEEDED??
    int flags =
      Intent.FLAG_ACTIVITY_NEW_TASK |
      Intent.FLAG_ACTIVITY_SINGLE_TOP |
      Intent.FLAG_ACTIVITY_CLEAR_TOP;
    popup.setFlags(flags);
    if (threadId > 0) {
      //Log.v("^^Found threadId (" + threadId + "), sending to Sms intent");
      popup.setData(Uri.withAppendedPath(THREAD_ID_CONTENT_URI, String.valueOf(threadId)));
    } else {
      return getSmsIntent();
    }
    return popup;
  }

  /**
   * 
   */
  public static Intent getSmsToIntentFromThreadId(Context context, String phoneNumber) {
    Intent popup = new Intent(Intent.ACTION_SENDTO);
    // should I be using FLAG_ACTIVITY_RESET_TASK_IF_NEEDED??
    int flags =
      Intent.FLAG_ACTIVITY_NEW_TASK |
      Intent.FLAG_ACTIVITY_SINGLE_TOP |
      Intent.FLAG_ACTIVITY_CLEAR_TOP;
    popup.setFlags(flags);
    if (!"".equals(phoneNumber)) {
      //Log.v("^^Found threadId (" + threadId + "), sending to Sms intent");
      popup.setData(Uri.parse(SMSTO_URI + Uri.encode(phoneNumber)));
    } else {
      return getSmsIntent();
    }
    return popup;
  }

  /**
   * 
   */
  public static void launchEmailToIntent(Context context, String subject, boolean includeDebug) {
    Intent msg = new Intent(Intent.ACTION_SEND);

    SharedPreferences myPrefs = PreferenceManager.getDefaultSharedPreferences(context);
    boolean donated = myPrefs.getBoolean(context.getString(R.string.pref_donated_key), false);

    StringBuilder body = new StringBuilder();

    if (includeDebug) {
      body.append(String.format("\n\n----------\nSysinfo - %s\nModel: %s\n\n",
          Build.FINGERPRINT, Build.MODEL));

      // Array of preference keys to include in email
      final String[] pref_keys = {
          context.getString(R.string.pref_enabled_key),
          context.getString(R.string.pref_timeout_key),
          context.getString(R.string.pref_privacy_key),
          context.getString(R.string.pref_dimscreen_key),
          context.getString(R.string.pref_markread_key),
          context.getString(R.string.pref_onlyShowOnKeyguard_key),
          context.getString(R.string.pref_show_buttons_key),
          context.getString(R.string.pref_button1_key),
          context.getString(R.string.pref_button2_key),
          context.getString(R.string.pref_button3_key),
          context.getString(R.string.pref_blur_key),
          context.getString(R.string.pref_popup_enabled_key),
          context.getString(R.string.pref_notif_enabled_key),
          context.getString(R.string.pref_notif_sound_key),
          context.getString(R.string.pref_vibrate_key),
          context.getString(R.string.pref_vibrate_pattern_key),
          context.getString(R.string.pref_vibrate_pattern_custom_key),
          context.getString(R.string.pref_flashled_key),
          context.getString(R.string.pref_flashled_color_key),
          context.getString(R.string.pref_notif_repeat_key),
          context.getString(R.string.pref_notif_repeat_times_key),
          context.getString(R.string.pref_notif_repeat_interval_key),
      };

      Map<String, ?> m = myPrefs.getAll();

      body.append(String.format("%s config -\n", subject));
      for (int i=0; i<pref_keys.length; i++) {
        try {
          body.append(String.format("%s: %s\n", pref_keys[i], m.get(pref_keys[i])));
        } catch (NullPointerException e) {
          // Nothing to do here
        }
      }

      // Add locale info
      body.append(String.format("locale: %s\n",
          context.getResources().getConfiguration().locale.getDisplayName()));

      // TODO: fix this up so users can attach system logs to the email
      // this almost works but for some reason the attachment never sends (while it still
      // appears in the draft email that is created) :(
      // Attach the log file if it exists
      //      Uri log = collectLogs(context);
      //      if (log != null) {
      //        msg.putExtra(Intent.EXTRA_STREAM, log);
      //      }
    }

    msg.putExtra(Intent.EXTRA_EMAIL, donated ? AUTHOR_CONTACT_INFO_DONATE : AUTHOR_CONTACT_INFO);
    msg.putExtra(Intent.EXTRA_SUBJECT, subject);
    msg.putExtra(Intent.EXTRA_TEXT, body.toString());

    msg.setType("message/rfc822");
    context.startActivity(Intent.createChooser(
        msg, context.getString(R.string.pref_sendemail_title)));
  }

  /**
   * Fetch output from logcat, dump it in a file and return the URI to the file
   */
  public static Uri collectLogs(Context context) {
    final String logfile = "log.txt";

    try {
      ArrayList<String> commandLine = new ArrayList<String>();
      commandLine.add("logcat");
      commandLine.add("-d");
      commandLine.add("AndroidRuntime:E");
      commandLine.add(Log.LOGTAG + ":V");
      commandLine.add("*:S");

      BufferedInputStream fin = new BufferedInputStream(
          Runtime.getRuntime().exec(commandLine.toArray(new String[0])).getInputStream());
      BufferedOutputStream fout = new BufferedOutputStream(
          context.openFileOutput(logfile, Context.MODE_WORLD_READABLE));

      // Copy output to a log file
      int i;
      do {
        i = fin.read();
        if (i != -1)
          fout.write(i);
      } while (i != -1);
      fin.close();
      fout.close();
    } catch (IOException e) {
      return null;
    } catch (SecurityException e) {
      return null;
    }

    return Uri.fromFile(context.getFileStreamPath(logfile));
  }

  /**
   * Return current unread message count from system db (sms and mms)
   * 
   * @param context
   * @return unread sms+mms message count
   */
  public static int getUnreadMessagesCount(Context context) {
    return getUnreadMessagesCount(context, 0, null);
  }

  /**
   * Return current unread message count from system db (sms and mms)
   * 
   * @param context
   * @param timestamp only messages before this timestamp will be counted
   * @return unread sms+mms message count
   */
  public static int getUnreadMessagesCount(Context context, long timestamp, String messageBody) {
    return getUnreadSmsCount(context, timestamp, messageBody) + getUnreadMmsCount(context);
  }

  /**
   * Return current unread message count from system db (sms only)
   * 
   * @param context
   * @return unread sms message count
   */
  public static int getUnreadSmsCount(Context context) {
    return getUnreadSmsCount(context, 0, null);
  }

  /**
   * Return current unread message count from system db (sms only)
   * 
   * @param context
   * @param timestamp only messages before this timestamp will be counted
   * @return unread sms message count
   */
  public static int getUnreadSmsCount(Context context, long timestamp, String messageBody) {

    if (Log.DEBUG) Log.v("getUnreadSmsCount()");

    final String[] projection = new String[] { SMSMMS_ID, "body" };
    final String selection = UNREAD_CONDITION;
    final String[] selectionArgs = null;
    final String sortOrder = "date DESC";

    //    if (timestamp > 0) {
    //      if (Log.DEBUG) Log.v("getUnreadSmsCount(), timestamp = " + timestamp);
    //      selection += " and date < ?";
    //      selectionArgs =
    //        new String[] { String.valueOf(timestamp - SmsMmsMessage.MESSAGE_COMPARE_TIME_BUFFER) };
    //    }

    int count = 0;

    Cursor cursor = context.getContentResolver().query(
        SMS_INBOX_CONTENT_URI,
        projection,
        selection,
        selectionArgs,
        sortOrder);

    if (cursor != null) {
      try {
        count = cursor.getCount();

        /*
         * We need to check if the message received matches the most recent one in the db
         * or not (to find out if our code ran before the system code or vice-versa)
         */
        if (messageBody != null && count > 0) {
          if (cursor.moveToFirst()) {
            /*
             * Check the most recent message, if the body does not match then it hasn't yet
             * been inserted into the system database, therefore we need to add one to our
             * total count
             */
            if (!messageBody.equals(cursor.getString(1))) {
              if (Log.DEBUG) Log.v("getUnreadSmsCount(): most recent message did not match body, adding 1 to count");
              count++;
            }
          }
        }
      } finally {
        cursor.close();
      }
    }

    /*
     * If count is still 0 and timestamp is set then its likely the system db had not updated
     * when this code ran, therefore let's add 1 so the notify will run correctly.
     */
    if (count == 0 && timestamp > 0) {
      count = 1;
    }

    // We ignored the latest incoming message so add one to the total count
    //    if (timestamp > 0) {
    //      if (Log.DEBUG) Log.v("adding 1 to unread, previous count was " + count);
    //      count += 1;
    //    }

    if (Log.DEBUG) Log.v("getUnreadSmsCount(): unread count = " + count);
    return count;
  }

  /**
   * Return current unread message count from system db (mms only)
   * 
   * @param context
   * @return unread mms message count
   */
  public static int getUnreadMmsCount(Context context) {

    final String selection = UNREAD_CONDITION;
    final String[] projection = new String[] { SMSMMS_ID };

    int count = 0;

    Cursor cursor = context.getContentResolver().query(
        MMS_INBOX_CONTENT_URI,
        projection,
        selection, null, null);

    if (cursor != null) {
      try {
        count = cursor.getCount();
      } finally {
        cursor.close();
      }
    }
    if (Log.DEBUG) Log.v("mms unread count = " + count);
    return count;
  }

  /*
   * 
   */
  public static SmsMmsMessage getSmsDetails(Context context,
      long ignoreThreadId, boolean unreadOnly) {

    final String[] projection =
      new String[] { "_id", "thread_id", "address", "person", "date", "body" };
    String selection = unreadOnly ? UNREAD_CONDITION : null;
    String[] selectionArgs = null;
    final String sortOrder = "date DESC";

    int count = 0;

    if (ignoreThreadId > 0) {
      selection = (selection == null) ? "" : selection + " and ";
      selection += "thread_id != ?";
      selectionArgs = new String[] { String.valueOf(ignoreThreadId) };
    }

    Cursor cursor = context.getContentResolver().query(
        SMS_INBOX_CONTENT_URI,
        projection,
        selection,
        selectionArgs,
        sortOrder);

    if (cursor != null) {
      try {
        count = cursor.getCount();
        if (count > 0) {
          cursor.moveToFirst();

          //					String[] columns = cursor.getColumnNames();
          //					for (int i=0; i<columns.length; i++) {
          //						Log.v("columns " + i + ": " + columns[i] + ": "
          //								+ cursor.getString(i));
          //					}

          long messageId = cursor.getLong(0);
          long threadId = cursor.getLong(1);
          String address = cursor.getString(2);
          long contactId = cursor.getLong(3);
          String contactId_string = String.valueOf(contactId);
          long timestamp = cursor.getLong(4);

          String body = cursor.getString(5);

          if (!unreadOnly) {
            count = 0;
          }

          SmsMmsMessage smsMessage = new SmsMmsMessage(
              context, address, contactId_string, body, timestamp,
              threadId, count, messageId, SmsMmsMessage.MESSAGE_TYPE_SMS);

          return smsMessage;

        }
      } finally {
        cursor.close();
      }
    }
    return null;
  }

  public static SmsMmsMessage getSmsDetails(Context context) {
    return getSmsDetails(context, 0);
  }

  public static SmsMmsMessage getSmsDetails(Context context, boolean unreadOnly) {
    return getSmsDetails(context, 0, unreadOnly);
  }

  public static SmsMmsMessage getSmsDetails(Context context, long ignoreThreadId) {
    return getSmsDetails(context, ignoreThreadId, true);
  }

  /*
   * 
   */
  public static SmsMmsMessage getMmsDetails(Context context, long ignoreThreadId) {

    final String[] projection = new String[] { "_id", "thread_id", "date", "sub", "sub_cs" };
    String selection = UNREAD_CONDITION;
    String[] selectionArgs = null;
    final String sortOrder = "date DESC";
    int count = 0;

    if (ignoreThreadId > 0) {
      selection += " and thread_id != ?";
      selectionArgs = new String[] { String.valueOf(ignoreThreadId) };
    }

    Cursor cursor = context.getContentResolver().query(
        MMS_INBOX_CONTENT_URI,
        projection,
        selection,
        selectionArgs,
        sortOrder);

    if (cursor != null) {
      try {
        count = cursor.getCount();
        if (count > 0) {
          cursor.moveToFirst();
          //          String[] columns = cursor.getColumnNames();
          //          for (int i=0; i<columns.length; i++) {
          //            Log.v("columns " + i + ": " + columns[i] + ": "
          //                + cursor.getString(i));
          //          }
          long messageId = cursor.getLong(0);
          String address = getMmsAddress(context, messageId);
          String contactName = getDisplayName(context, address).trim();
          String contactId = getPersonIdFromEmail(context, address);

          long threadId = cursor.getLong(1);
          long timestamp = cursor.getLong(2) * 1000;
          String subject = cursor.getString(3);

          SmsMmsMessage mmsMessage = new SmsMmsMessage(
              context, contactId, contactName, address, subject, timestamp,
              messageId, threadId, count, SmsMmsMessage.MESSAGE_TYPE_MMS);

          return mmsMessage;

        }
      } finally {
        cursor.close();
      }
    }
    return null;
  }

  public static SmsMmsMessage getMmsDetails(Context context) {
    return getMmsDetails(context, 0);
  }

  public static String getMmsAddress(Context context, long message_id) {
    final String[] projection =  new String[] { "address", "contact_id", "charset", "type" };
    final String selection = "type=137"; // "type="+ PduHeaders.FROM,

    Uri.Builder builder = MMS_CONTENT_URI.buildUpon();
    builder.appendPath(String.valueOf(message_id)).appendPath("addr");

    Cursor cursor = context.getContentResolver().query(
        builder.build(),
        projection,
        selection,
        null, null);

    if (cursor != null) {
      try {
        if (cursor.moveToFirst()) {
          // Apparently contact_id is always empty in this table so we can't get it from here

          // Just return the address
          return cursor.getString(0);
        }
      } finally {
        cursor.close();
      }
    }

    return context.getString(android.R.string.unknownName);
  }

  public static final Pattern NAME_ADDR_EMAIL_PATTERN =
    Pattern.compile("\\s*(\"[^\"]*\"|[^<>\"]+)\\s*<([^<>]+)>\\s*");

  public static final Pattern QUOTED_STRING_PATTERN =
    Pattern.compile("\\s*\"([^\"]*)\"\\s*");

  private static String getEmailDisplayName(String displayString) {
    Matcher match = QUOTED_STRING_PATTERN.matcher(displayString);
    if (match.matches()) {
      return match.group(1);
    }
    return displayString;
  }

  /**
   * Get the display name of an email address. If the address already contains
   * the name, parse and return it. Otherwise, query the contact database. Cache
   * query results for repeated queries.
   */
  private static String getDisplayName(Context context, String email) {
    Matcher match = NAME_ADDR_EMAIL_PATTERN.matcher(email);
    if (match.matches()) {
      // email has display name, return that
      return getEmailDisplayName(match.group(1));
    }

    // otherwise let's check the contacts list for a user with this email
    Cursor cursor = context.getContentResolver().query(
        Contacts.ContactMethods.CONTENT_EMAIL_URI,
        new String[] { Contacts.ContactMethods.NAME },
        Contacts.ContactMethods.DATA + " = ?",
        new String[] { email }, null);

    if (cursor != null) {
      try {
        int columnIndex =
          cursor.getColumnIndexOrThrow(Contacts.ContactMethods.NAME);
        while (cursor.moveToNext()) {
          String name = cursor.getString(columnIndex);
          if (!TextUtils.isEmpty(name)) {
            return name;
          }
        }
      } finally {
        cursor.close();
      }
    }
    return email;
  }

  /*
   * Get the most recent unread message, returning in a SmsMmsMessage which is
   * suitable for updating the notification.  Optional param is the message object:
   * we can pull out the thread id of this message in the case the user is "replying"
   * to the message and we should ignore all messages in the thread when working out
   * what to display in the notification bar (as these messages will soon be marked read
   * but we can't be sure when the messaging app will actually start).
   * 
   */
  public static SmsMmsMessage getRecentMessage(Context context, SmsMmsMessage ignoreMessage) {
    long ignoreThreadId = 0;

    if (ignoreMessage != null) {
      ignoreThreadId = ignoreMessage.getThreadId();
    }

    SmsMmsMessage smsMessage = getSmsDetails(context, ignoreThreadId);
    SmsMmsMessage mmsMessage = getMmsDetails(context, ignoreThreadId);

    if (mmsMessage == null && smsMessage != null) {
      return smsMessage;
    }

    if (mmsMessage != null && smsMessage == null) {
      return mmsMessage;
    }

    if (mmsMessage != null && smsMessage != null) {
      if (mmsMessage.getTimestamp() < smsMessage.getTimestamp()) {
        return mmsMessage;
      }
      return smsMessage;
    }

    return null;
  }

  public static SmsMmsMessage getRecentMessage(Context context) {
    return getRecentMessage(context, null);
  }

  /**
   * Read the PDUs out of an {@link #SMS_RECEIVED_ACTION} or a
   * {@link #DATA_SMS_RECEIVED_ACTION} intent.
   * 
   * @param intent
   *           the intent to read from
   * @return an array of SmsMessages for the PDUs
   */
  public static final SmsMessage[] getMessagesFromIntent(Intent intent) {
    Object[] messages = (Object[]) intent.getSerializableExtra("pdus");
    if (messages == null) {
      return null;
    }
    if (messages.length == 0) {
      return null;
    }

    byte[][] pduObjs = new byte[messages.length][];

    for (int i = 0; i < messages.length; i++) {
      pduObjs[i] = (byte[]) messages[i];
    }
    byte[][] pdus = new byte[pduObjs.length][];
    int pduCount = pdus.length;
    SmsMessage[] msgs = new SmsMessage[pduCount];
    for (int i = 0; i < pduCount; i++) {
      pdus[i] = pduObjs[i];
      msgs[i] = SmsMessage.createFromPdu(pdus[i]);
    }
    return msgs;
  }

  /**
   * This function will see if the most recent activity was the system messaging app so we can suppress
   * the popup as the user is likely already viewing messages or composing a new message
   */
  public static final boolean inMessagingApp(Context context) {
    // TODO: move these to static strings somewhere
    final String PACKAGE_NAME = "com.android.mms";
    //final String COMPOSE_CLASS_NAME = "com.android.mms.ui.ComposeMessageActivity";
    final String CONVO_CLASS_NAME = "com.android.mms.ui.ConversationList";

    ActivityManager mAM = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);

    List<RunningTaskInfo> mRunningTaskList = mAM.getRunningTasks(1);
    Iterator<RunningTaskInfo> mIterator = mRunningTaskList.iterator();
    if (mIterator.hasNext()) {
      RunningTaskInfo mRunningTask = mIterator.next();
      if (mRunningTask != null) {
        ComponentName runningTaskComponent = mRunningTask.baseActivity;

        //				Log.v("baseActivity = " + mRunningTask.baseActivity.toString());
        //				Log.v("topActivity = " + mRunningTask.topActivity.toString());

        if (PACKAGE_NAME.equals(runningTaskComponent.getPackageName()) &&
            CONVO_CLASS_NAME.equals(runningTaskComponent.getClassName())) {
          if (Log.DEBUG) Log.v("User in messaging app - from running task");
          return true;
        }
      }
    }

    /*
		List<RecentTaskInfo> mActivityList = mAM.getRecentTasks(1, 0);
		Iterator<RecentTaskInfo> mIterator = mActivityList.iterator();

		if (mIterator.hasNext()) {
			RecentTaskInfo mRecentTask = (RecentTaskInfo) mIterator.next();
			Intent recentTaskIntent = mRecentTask.baseIntent;

			if (recentTaskIntent != null) {
				ComponentName recentTaskComponentName = recentTaskIntent.getComponent();
				if (recentTaskComponentName != null) {
					String recentTaskClassName = recentTaskComponentName.getClassName();
					if (PACKAGE_NAME.equals(recentTaskComponentName.getPackageName()) &&
							(COMPOSE_CLASS_NAME.equals(recentTaskClassName) ||
							 CONVO_CLASS_NAME.equals(recentTaskClassName))) {
						if (Log.DEBUG) Log.v("User in messaging app");
						return true;
					}
				}
			}
		}
     */

    /*
		These appear to be the 2 main intents that mean the user is using the messaging app

		action "android.intent.action.MAIN"
		data null
		class "com.android.mms.ui.ConversationList"
		package "com.android.mms"

		action "android.intent.action.VIEW"
		data "content://mms-sms/threadID/3"
		class "com.android.mms.ui.ComposeMessageActivity"
		package "com.android.mms"
     */

    return false;
  }

  /**
   * Enables or disables the main SMS receiver
   */
  public static void enableSMSPopup(Context context, boolean enable) {
    PackageManager pm = context.getPackageManager();
    ComponentName cn = new ComponentName(context, SmsReceiver.class);

    // Update preference so it reflects in the preference activity
    SharedPreferences myPrefs = PreferenceManager.getDefaultSharedPreferences(context);
    SharedPreferences.Editor settings = myPrefs.edit();
    settings.putBoolean(context.getString(R.string.pref_enabled_key), enable);
    settings.commit();

    if (enable) {
      if (Log.DEBUG) Log.v("SMSPopup receiver is enabled");
      pm.setComponentEnabledSetting(cn,
          PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
          PackageManager.DONT_KILL_APP);

      // Send a broadcast to disable other SMS Popup apps
      disableOtherSMSPopup(context);

    } else {
      if (Log.DEBUG) Log.v("SMSPopup receiver is disabled");
      pm.setComponentEnabledSetting(cn,
          PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
          PackageManager.DONT_KILL_APP);
    }
  }

  public static void disableOtherSMSPopup(Context context) {
    // Send a broadcast to disable SMS Popup Pro
    Intent i = new Intent(ExternalEventReceiver.ACTION_SMSPOPUP_DISABLE);
    i.setClassName("net.everythingandroid.smspopuppro", "net.everythingandroid.smspopuppro.ExternalEventReceiver");
    context.sendBroadcast(i);
  }

  /**
   * Fetch the current device Android OS platform number.
   * 
   * TODO: once Cupcake support is no longer needed the system var
   * android.os.Build.VERSION.SDK_INT can be used instead.
   * 
   * @return SDK version number
   */
  public static int getSDKVersionNumber() {
    int version_sdk;
    try {
      version_sdk = Integer.valueOf(android.os.Build.VERSION.SDK);
    } catch (NumberFormatException e){
      version_sdk = 0;
    }
    return version_sdk;
  }
}