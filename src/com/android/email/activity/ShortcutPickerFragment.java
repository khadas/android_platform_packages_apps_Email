/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.email.activity;

import com.android.email.R;
import com.android.emailcommon.provider.EmailContent.Account;
import com.android.emailcommon.provider.EmailContent.AccountColumns;
import com.android.emailcommon.provider.EmailContent.MailboxColumns;
import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.provider.Mailbox;

import android.app.Activity;
import android.app.ListFragment;
import android.app.LoaderManager.LoaderCallbacks;
import android.content.Context;
import android.content.CursorLoader;
import android.content.Loader;
import android.database.Cursor;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.SimpleCursorAdapter;
import android.widget.AdapterView.OnItemClickListener;

/**
 * Fragment containing a list of accounts to show during shortcut creation.
 * <p>
 * NOTE: In order to receive callbacks, the activity containing this fragment must implement
 * the {@link PickerCallback} interface.
 */
public abstract class ShortcutPickerFragment extends ListFragment
        implements OnItemClickListener, LoaderCallbacks<Cursor> {
    /** Callback methods. Enclosing activities must implement to receive fragment notifications. */
    public static interface PickerCallback {
        /** Invoked when an account and mailbox have been selected. */
        public void onSelected(Account account, long mailboxId);
        /** Required data is missing; either the account and/or mailbox */
        public void onMissingData(boolean missingAccount, boolean missingMailbox);
    }

    /** A no-op callback */
    private final PickerCallback EMPTY_CALLBACK = new PickerCallback() {
        @Override public void onSelected(Account account, long mailboxId){ getActivity().finish(); }
        @Override public void onMissingData(boolean missingAccount, boolean missingMailbox) { }
    };
    private final static int LOADER_ID = 0;
    private final static int[] TO_VIEWS = new int[] {
        android.R.id.text1,
    };

    PickerCallback mCallback = EMPTY_CALLBACK;
    /** Cursor adapter that provides either the account or mailbox list */
    private SimpleCursorAdapter mAdapter;

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);

        if (activity instanceof PickerCallback) {
            mCallback = (PickerCallback) activity;
        }
        final String[] fromColumns = getFromColumns();
        mAdapter = new SimpleCursorAdapter(activity,
            android.R.layout.simple_expandable_list_item_1, null, fromColumns, TO_VIEWS, 0);
        setListAdapter(mAdapter);

        getLoaderManager().initLoader(LOADER_ID, null, this);
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        ListView listView = getListView();
        listView.setOnItemClickListener(this);
        listView.setItemsCanFocus(false);
    }

    @Override
    public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
        mAdapter.swapCursor(data);
    }

    @Override
    public void onLoaderReset(Loader<Cursor> loader) {
        mAdapter.swapCursor(null);
    }

    /** Returns the cursor columns to map into list */
    abstract String[] getFromColumns();

    /** Account picker */
    public static class AccountShortcutPickerFragment extends ShortcutPickerFragment {
        private final static String[] ACCOUNT_FROM_COLUMNS = new String[] {
            AccountColumns.DISPLAY_NAME,
        };

        @Override
        public void onActivityCreated(Bundle savedInstanceState) {
            super.onActivityCreated(savedInstanceState);
            getActivity().setTitle(R.string.account_shortcut_picker_title);
        }

        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
            Cursor cursor = (Cursor) parent.getItemAtPosition(position);
            selectAccountCursor(cursor);
        }

        @Override
        public Loader<Cursor> onCreateLoader(int id, Bundle args) {
            Context context = getActivity();
            // TODO Add ability to insert special account "all accounts"
            return new CursorLoader(
                context, Account.CONTENT_URI, Account.CONTENT_PROJECTION, null, null, null);
        }

        @Override
        public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
            // if there is only one account, auto-select it
            // No accounts; close the dialog
            if (data.getCount() == 0) {
                mCallback.onMissingData(true, false);
                return;
            }
            if (data.getCount() == 1 && data.moveToFirst()) {
                selectAccountCursor(data);
                return;
            }
            super.onLoadFinished(loader, data);
        }

        @Override
        String[] getFromColumns() {
            return ACCOUNT_FROM_COLUMNS;
        }

        /** Selects the account specified by the given cursor */
        private void selectAccountCursor(Cursor cursor) {
            Account account = new Account();
            account.restore(cursor);
            ShortcutPickerFragment fragment = new MailboxShortcutPickerFragment();
            final Bundle args = new Bundle();
            args.putParcelable(MailboxShortcutPickerFragment.ARG_ACCOUNT, account);
            fragment.setArguments(args);
            getFragmentManager()
                .beginTransaction()
                    .replace(R.id.shortcut_list, fragment)
                    .addToBackStack(null)
                    .commit();
        }
    }

    /** Mailbox picker */
    public static class MailboxShortcutPickerFragment extends ShortcutPickerFragment {
        static final String ARG_ACCOUNT = "MailboxShortcutPickerFragment.account";
        private final static String[] MAILBOX_FROM_COLUMNS = new String[] {
            MailboxColumns.DISPLAY_NAME,
        };
        /** Loader projection used for IMAP & POP3 accounts */
        private final static String[] IMAP_PROJECTION = new String [] {
            MailboxColumns.ID, MailboxColumns.SERVER_ID + " as " + MailboxColumns.DISPLAY_NAME
        };
        /** Loader projection used for EAS accounts */
        private final static String[] EAS_PROJECTION = new String [] {
            MailboxColumns.ID, MailboxColumns.DISPLAY_NAME
        };
        // TODO This is identical to MailboxesAdapter#ALL_MAILBOX_SELECTION; any way we can create a
        // common selection? Move this to the Mailbox class?
        private final static String ALL_MAILBOX_SELECTION = MailboxColumns.ACCOUNT_KEY + "=?" +
                " AND " + Mailbox.USER_VISIBLE_MAILBOX_SELECTION;

        /** The currently selected account */
        private Account mAccount;

        @Override
        public void onAttach(Activity activity) {
            // Need to setup the account first thing
            mAccount = getArguments().getParcelable(ARG_ACCOUNT);
            super.onAttach(activity);
        }

        @Override
        public void onActivityCreated(Bundle savedInstanceState) {
            super.onActivityCreated(savedInstanceState);
            getActivity().setTitle(R.string.mailbox_shortcut_picker_title);
        }

        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
            Cursor cursor = (Cursor) parent.getItemAtPosition(position);
            long mailboxId = cursor.getLong(Mailbox.CONTENT_ID_COLUMN);
            mCallback.onSelected(mAccount, mailboxId);
        }

        @Override
        public Loader<Cursor> onCreateLoader(int id, Bundle args) {
            Context context = getActivity();
            // TODO Add ability to insert special mailboxes like "starred", etc...
            // TODO Create a fully-qualified path name for Exchange accounts [code should also work
            //      for MoveMessageToDialog.java]
            HostAuth recvAuth = mAccount.getOrCreateHostAuthRecv(context);
            final String[] projection;
            final String orderBy;
            if (recvAuth.isEasConnection()) {
                projection = EAS_PROJECTION;
                orderBy = MailboxColumns.DISPLAY_NAME;
            } else {
                projection = IMAP_PROJECTION;
                orderBy = MailboxColumns.SERVER_ID;
            }
            return new CursorLoader(
                context, Mailbox.CONTENT_URI, projection, ALL_MAILBOX_SELECTION,
                new String[] { Long.toString(mAccount.mId) }, orderBy);
        }

        @Override
        public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
            // No accounts; close the dialog
            if (data.getCount() == 0) {
                mCallback.onMissingData(false, true);
                return;
            }
            // if there is only one mailbox, auto-select it
            if (data.getCount() == 1 && data.moveToFirst()) {
                long mailboxId = data.getLong(Mailbox.CONTENT_ID_COLUMN);
                mCallback.onSelected(mAccount, mailboxId);
                return;
            }
            super.onLoadFinished(loader, data);
        }

        @Override
        String[] getFromColumns() {
            return MAILBOX_FROM_COLUMNS;
        }
    }
}
