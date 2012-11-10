package com.brainydroid.daydreaming.background;

import java.util.ArrayList;
import java.util.HashSet;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

import com.brainydroid.daydreaming.db.Poll;
import com.brainydroid.daydreaming.db.PollsStorage;
import com.brainydroid.daydreaming.db.QuestionsStorage;
import com.brainydroid.daydreaming.network.CryptoStorage;
import com.brainydroid.daydreaming.network.CryptoStorageCallback;
import com.brainydroid.daydreaming.network.HttpConversationCallback;
import com.brainydroid.daydreaming.network.HttpGetData;
import com.brainydroid.daydreaming.network.HttpGetTask;
import com.brainydroid.daydreaming.network.ServerTalker;
import com.google.gson.Gson;

public class SyncService extends Service {

	private static String TAG = "SyncService";

	private static String BS_EXP_APP_ID = "daydreaming";
	private static String BS_SERVER_NAME = "http://mehho.net:5000/";

	private static String QUESTIONS_VERSION_URL = "http://mehho.net:5001/questionsVersion";
	private static String QUESTIONS_URL = "http://mehho.net:5001/questions.json";

	private StatusManager status;
	private PollsStorage pollsStorage;
	private QuestionsStorage questionsStorage;
	private CryptoStorage cryptoStorage;
	private ServerTalker serverTalker;
	private Gson gson;
	private ArrayList<Poll> uploadablePolls;
	private HashSet<Integer> pollsLeftToUpload;
	private boolean updateQuestionsDone = false;
	private boolean uploadAnswersDone = false;

	@Override
	public void onCreate() {

		// Debug
		Log.d(TAG, "[fn] onCreate");

		super.onCreate();

		initVarsAndUpdates();
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {

		// Debug
		Log.d(TAG, "[fn] onStartCommand");

		super.onStartCommand(intent, flags, startId);

		return START_REDELIVER_INTENT;
	}

	@Override
	public void onDestroy() {

		// Debug
		Log.d(TAG, "[fn] onDestroy");

		super.onDestroy();
	}

	@Override
	public IBinder onBind(Intent intent) {

		// Debug
		Log.d(TAG, "[fn] onBind");

		// Don't allow binding
		return null;
	}

	private void initVarsAndUpdates() {

		// Debug
		Log.d(TAG, "[fn] initVarsAndUpdates");

		status = StatusManager.getInstance(this);

		if (status.isDataEnabled()) {

			// Info
			Log.i(TAG, "data connection enabled -> starting sync tasks");

			Toast.makeText(this, "SyncService: starting sync...",
					Toast.LENGTH_SHORT).show();

			pollsStorage = PollsStorage.getInstance(this);
			questionsStorage = QuestionsStorage.getInstance(this);
			gson = new Gson();

			CryptoStorageCallback callback = new CryptoStorageCallback() {

				private final String TAG = "CryptoStorageCallback";

				@Override
				public void onCryptoStorageReady(boolean hasKeyPairAndMaiId) {

					// Debug
					Log.d(TAG, "(callback) onCryptoStorageReady");

					serverTalker = ServerTalker.getInstance(BS_SERVER_NAME, cryptoStorage);

					if (hasKeyPairAndMaiId && status.isDataEnabled()) {
						asyncUpdateQuestions();
						asyncUploadAnswers();
					}
				}

			};

			// This will launch all calls through the callbacks
			cryptoStorage = CryptoStorage.getInstance(this, BS_SERVER_NAME, callback);
		} else {

			// Info
			Log.i(TAG, "no data connection available -> exiting");

			Toast.makeText(this, "SyncService: no internet connection",
					Toast.LENGTH_SHORT).show();
			stopSelf();
		}
	}

	private void asyncUpdateQuestions() {

		// Debug
		Log.d(TAG, "[fn] asyncUpdateQuestions");

		// FIXME: There might be a problem if the service is started from an Activity, and the
		// orientation of the display changes. That will stop and restart the worker process.
		// See http://developer.android.com/guide/components/processes-and-threads.html ,
		// right above the "Thread-safe methods" title.


		final HttpConversationCallback updateQuestionsCallback = new HttpConversationCallback() {

			private final String TAG = "HttpConversationCallback";

			@Override
			public void onHttpConversationFinished(boolean success, String serverAnswer) {

				// Debug
				Log.d(TAG, "[fn] (updateQuestionsCallback) onHttpConversationFinished");

				if (success) {

					// Info
					Log.i(TAG, "successfully retrieved new questions.json from server");

					Toast.makeText(SyncService.this,
							"SyncService: new questions downloaded from server",
							Toast.LENGTH_SHORT).show();
					questionsStorage.importQuestions(serverAnswer);
				} else {
					// Warning
					Log.w(TAG, "error while retrieving new questions.json from server");
				}

				setUpdateQuestionsDone();
			}

		};

		HttpConversationCallback fullCallback = new HttpConversationCallback() {

			private final String TAG = "HttpConversationCallback";

			@Override
			public void onHttpConversationFinished(boolean success, String serverAnswer) {

				// Debug
				Log.d(TAG, "[fn] (fullCallback) onHttpConversationFinished");

				boolean willGetQuestions = false;

				if (success) {

					// Info
					Log.i(TAG, "successfully retrieved questionsVersion from server");

					try {
						int serverQuestionsVersion = Integer.parseInt(serverAnswer.split("\n")[0]);

						if (serverQuestionsVersion != questionsStorage.getQuestionsVersion()) {

							// Info
							Log.i(TAG, "server's questionsVersion is different from the local one -> trying to update questions");

							willGetQuestions = true;

							HttpGetData updateQuestionsData = new HttpGetData(QUESTIONS_URL,
									updateQuestionsCallback);
							HttpGetTask updateQuestionsTask = new HttpGetTask();
							updateQuestionsTask.execute(updateQuestionsData);
						} else {
							Toast.makeText(SyncService.this,
									"SyncService: no new questions to download",
									Toast.LENGTH_SHORT).show();
						}
					} catch (Exception e) {
						// Warning
						Log.w(TAG, "error while parsing questionsVersion answer from server");
					}
				} else {
					// Warning
					Log.w(TAG, "error while retrieving questionsVersion from server");
				}

				if (!willGetQuestions) {
					setUpdateQuestionsDone();
				}
			}

		};

		HttpGetData getQuestionsVersionData = new HttpGetData(QUESTIONS_VERSION_URL,
				fullCallback);
		HttpGetTask getQuestionsVersionTask = new HttpGetTask();
		getQuestionsVersionTask.execute(getQuestionsVersionData);
	}

	private void asyncUploadAnswers() {

		// Debug
		Log.d(TAG, "[fn] asyncUploadAnswers");

		// FIXME: There might be a problem if the service is started from an Activity, and the
		// orientation of the display changes. That will stop and restart the worker process.
		// See http://developer.android.com/guide/components/processes-and-threads.html ,
		// right above the "Thread-safe methods" title.

		uploadablePolls = pollsStorage.getUploadablePolls();

		if (uploadablePolls == null) {

			// Info
			Log.i(TAG, "no polls to upload -> exiting");

			Toast.makeText(this,
					"SyncService: no polls to upload",
					Toast.LENGTH_SHORT).show();

			setUploadPollsDone();
			return;
		}

		// Info
		Log.i(TAG, "trying to upload " + uploadablePolls.size() + " polls");

		Toast.makeText(this,
				"SyncService: trying to upload " + uploadablePolls.size() + " polls",
				Toast.LENGTH_SHORT).show();

		pollsLeftToUpload = new HashSet<Integer>();
		for (Poll poll : uploadablePolls) {
			pollsLeftToUpload.add(poll.getId());
		}

		for (Poll poll : uploadablePolls) {

			final int pollId = poll.getId();

			HttpConversationCallback callback = new HttpConversationCallback() {

				private final String TAG = "HttpConversationCallback";

				@Override
				public void onHttpConversationFinished(boolean success, String serverAnswer) {

					// Debug
					Log.d(TAG, "(callback) onHttpConversationFinished");

					if (success) {

						// Info
						Log.i(TAG, "successfully uploaded poll (id: " +
								pollId + ") to server (serverAnswer: " +
								serverAnswer + ")");

						Toast.makeText(SyncService.this,
								"SyncService: uploaded poll (id: " + pollId +
								") (serverAnswer: " + serverAnswer + ")",
								Toast.LENGTH_LONG).show();

						pollsStorage.removePoll(pollId);
						setUploadPollDone(pollId);
					} else {

						// Warning
						Log.w(TAG, "error while upload poll (id: " + pollId + ") to server");

						setUploadPollDone(pollId);
					}
				}

			};

			serverTalker.signAndUploadData(BS_EXP_APP_ID, gson.toJson(poll), callback);
		}
	}

	private void setUpdateQuestionsDone() {

		// Debug
		Log.d(TAG, "[fn] setUpdateQuestionsDone");

		updateQuestionsDone = true;
		stopSelfIfAllDone();
	}

	private void setUploadPollDone(int pollId) {

		// Debug
		Log.d(TAG, "[fn] setUploadPollDone");

		pollsLeftToUpload.remove(pollId);

		if (pollsLeftToUpload.size() == 0) {
			setUploadPollsDone();
		}
	}

	private void setUploadPollsDone() {

		// Debug
		Log.d(TAG, "[fn] setUploadPollsDone");

		uploadAnswersDone = true;
		stopSelfIfAllDone();
	}

	private void stopSelfIfAllDone() {

		// Debug
		Log.d(TAG, "[fn] stopSelfIfAllDone");

		if (updateQuestionsDone && uploadAnswersDone) {
			stopSelf();
		}
	}
}
