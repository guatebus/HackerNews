package com.airlocksoftware.hackernews.fragment;

import android.app.ActionBar;
import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.Loader;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.airlocksoftware.hackernews.R;
import com.airlocksoftware.hackernews.activity.MainActivity;
import com.airlocksoftware.hackernews.adapter.StoryAdapter;
import com.airlocksoftware.hackernews.data.AppData;
import com.airlocksoftware.hackernews.interfaces.SharePopupInterface;
import com.airlocksoftware.hackernews.interfaces.TabletLayout;
import com.airlocksoftware.hackernews.loader.StoryLoader;
import com.airlocksoftware.hackernews.model.Comment;
import com.airlocksoftware.hackernews.model.Page;
import com.airlocksoftware.hackernews.model.Request;
import com.airlocksoftware.hackernews.model.Result;
import com.airlocksoftware.hackernews.model.Story;
import com.airlocksoftware.hackernews.parser.StoryParser.StoryResponse;
import com.airlocksoftware.hackernews.view.SharePopup;
import com.airlocksoftware.holo.actionbar.ActionBarButton;
import com.airlocksoftware.holo.actionbar.ActionBarButton.Priority;
import com.airlocksoftware.holo.actionbar.interfaces.ActionBarClient;
import com.airlocksoftware.holo.actionbar.interfaces.ActionBarController;
import com.airlocksoftware.holo.image.IconView;
import com.airlocksoftware.holo.utils.Utils;
import com.airlocksoftware.holo.utils.ViewUtils;
import com.airlocksoftware.v3.fragment.BaseFragment;
import com.airlocksoftware.v3.menu.TextSettingsMenu;

import javax.inject.Inject;

import butterknife.ButterKnife;
import butterknife.InjectView;
import hugo.weaving.DebugLog;
import uk.co.senab.actionbarpulltorefresh.library.ActionBarPullToRefresh;
import uk.co.senab.actionbarpulltorefresh.library.PullToRefreshLayout;
import uk.co.senab.actionbarpulltorefresh.library.listeners.OnRefreshListener;

public class StoryFragment extends BaseFragment implements ActionBarClient,
        LoaderManager.LoaderCallbacks<StoryResponse>, OnRefreshListener, ActionBar.OnNavigationListener {

  // State
  private StoryAdapter mAdapter;
  private Page mPage = Page.FRONT;
  private Request mRequest = Request.NEW;
  private Result mLastResult = Result.EMPTY;
  private boolean mIsLoading = false;
  private boolean mShouldRestoreListState;

  // Activity interfaces
  TabletLayout mTabletLayout = null;
  StoryFragment.Callbacks mCallbacks = null;

  /* New Views */
  @InjectView(R.id.pull_to_refresh) PullToRefreshLayout mPullToRefresh;

  /* ActionBar */
  @Inject ActionBar mActionBar;
  private ArrayAdapter<CharSequence> mActionBarSpinnerAdapter;

  // Views
  View mError, mLoading;
  private ListView mList;
  private ActionBarButton mRefreshButton;
  private View mMoreButton;
  private TextView mMoreButtonText;
  private IconView mMoreIcon;

  // Listeners
  private OnClickListener mMoreListener = new OnClickListener() {
    @Override
    public void onClick(View v) {
      mRequest = Request.MORE;

      // change more link text & icon
      mMoreButtonText.setText(getActivity().getString(R.string.loading_));
      mMoreIcon.setVisibility(View.GONE);

      getLoaderManager().restartLoader(0, null, StoryFragment.this);
    }
  };

  private OnClickListener mRefreshListener = new OnClickListener() {
    @Override
    public void onClick(View v) {
      mRequest = Request.REFRESH;
      getLoaderManager().restartLoader(0, null, StoryFragment.this);
    }
  };

  // Constants
  public static final String PAGE = StoryFragment.class.getSimpleName() + ".page";

  @SuppressWarnings("unused")
  private static final String TAG = StoryFragment.class.getSimpleName();

  // Constructors
  public StoryFragment() {
    // default empty constructor
  }

  // Fragment Lifecycle
  @Override
  public void onAttach(Activity activity) {
    super.onAttach(activity);

    // get StoryFragment.Callbacks
    if (activity instanceof StoryFragment.Callbacks) {
      mCallbacks = (Callbacks) activity;
    } else {
      throw new RuntimeException(
              "The activity StoryFragment was attached to is required to implement the StoryFragment.Callbacks interface.");
    }

    // get TabletLayoutInterface
    if (activity instanceof TabletLayout) {
      mTabletLayout = (TabletLayout) activity;
    } else {
      throw new RuntimeException(
              "The activity StoryFragment was attached to is required to implement the TabletLayoutInterface interface.");
    }
  }

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    // start loading if this Fragment is part of the layout
    if (mCallbacks.storyFragmentIsInLayout()) {
      getLoaderManager().initLoader(0, null, this);
    }
  }

  @Override
  public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    View view = inflater.inflate(R.layout.frg_stories, container, false);
    ButterKnife.inject(this, view);
    
    return view;
  }

  @Override
  public void onActivityCreated(Bundle savedInstanceState) {
    super.onActivityCreated(savedInstanceState);

    if (!mCallbacks.storyFragmentIsInLayout()) {
      // if this Fragment is no longer part of the layout, return early
      return;
    }

    findViews();
    setupMoreButton();
    setupAdapter();

    /* v3 */
    initPullToRefresh();
    initActionBar();

    // if in tablet layout, change background & cache color hint of the list to a different color
    if (mTabletLayout.isTabletLayout()) {
      setupTabletBackgroundColors();
    }

    // restoring saved state
    if (savedInstanceState != null) {
      mPage = ((Page) savedInstanceState.getSerializable(PAGE));
      mAdapter.onRestoreInstanceState(savedInstanceState);
    }

    mShouldRestoreListState = true;

    refreshContentVisibility();
  }

  private void initActionBar() {
    /* Setup spinner */
    mActionBar.setDisplayShowTitleEnabled(false);
    int layoutResId = R.layout.vw_story_actionbar;
    mActionBarSpinnerAdapter = ArrayAdapter.createFromResource(getActivity(), R.array.story_pages, layoutResId);
    mActionBarSpinnerAdapter.setDropDownViewResource(android.R.layout.simple_dropdown_item_1line);
    mActionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_LIST);
    mActionBar.setListNavigationCallbacks(mActionBarSpinnerAdapter, this);
  }

  private void initPullToRefresh() {
        /* setup pull to refresh */
    ActionBarPullToRefresh.from(getActivity())
            .allChildrenArePullable()
            .listener(this)
            .setup(mPullToRefresh);
  }

  @DebugLog
  @Override public void onResume() {
    super.onResume();
    getBus().register(mAdapter);
  }

  @DebugLog
  @Override public void onPause() {
    getBus().unregister(mAdapter);

    /* Save list state to shared prefs (since we kill the activity when we switch to the comments list) */
    FragmentActivity activity = getActivity();
    if (activity == null || mList == null) {
      return;
    }
    new AppData(activity).saveStoryListPosition(mList.getFirstVisiblePosition());
    super.onPause();
  }


  @Override public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
    inflater.inflate(R.menu.stories, menu);
  }

  @Override public boolean onOptionsItemSelected(MenuItem item) {
    switch(item.getItemId()) {
      case R.id.action_text_settings:
        View textSettingsButton = getActivity().findViewById(R.id.action_text_settings);
//        TextSettingsMenu textSettingsMenu = new TextSettingsMenu(getActivity(), textSettingsButton);
//        textSettingsMenu.show();

        TextSettingsMenu textSettingsMenu = new TextSettingsMenu(getActivity());
        textSettingsMenu.show(textSettingsButton);
//        textSettingsMenu.showAsDropDown(textSettingsButton);
//        textSettingsMenu.showAtLocation(textSettingsButton, CENTER_HORIZONTAL | BOTTOM, 0, 0);

//        LayoutInflater inflater = getActivity().getLayoutInflater();
//        View layout = inflater.inflate(R.layout.popup_text_settings, null);
//        final PopupWindow popup = new PopupWindow(layout, WRAP_CONTENT, WRAP_CONTENT, true);
//        popup.setAnimationStyle(R.style.TextSettingsMenu_Animation);
//        popup.showAtLocation(textSettingsButton, CENTER_HORIZONTAL | BOTTOM, 0, 0);
//
//        Button btnClosePopup = (Button) layout.findViewById(R.id.btn_close_popup);
//        btnClosePopup.setOnClickListener(new OnClickListener() {
//          @Override public void onClick(View v) {
//            popup.dismiss();
//          }
//        });

        return true;
      case R.id.action_search:
        Toast.makeText(getActivity(), "Display search", Toast.LENGTH_LONG).show();
        return true;
      default:
        return super.onOptionsItemSelected(item);
    }
  }

  @SuppressWarnings("deprecation")
  private void setupTabletBackgroundColors() {
    Resources res = getActivity().getResources();

    // bg
    int tabletBgResId = Utils.getThemedResourceId(getActivity(), R.attr.bgStorylistTablet);
    Drawable tabletBg = res.getDrawable(tabletBgResId);
    ViewUtils.fixDrawableRepeat(tabletBg);

    mList.setBackgroundDrawable(tabletBg);
    mError.setBackgroundDrawable(tabletBg);
    mLoading.setBackgroundDrawable(tabletBg);

    // cache color hint
    int cacheColorHintResId = Utils.getThemedResourceId(getActivity(), R.attr.cacheColorHintStorylistTablet);
    mList.setCacheColorHint(res.getColor(cacheColorHintResId));
  }

  private void setupAdapter() {
    SharePopup popup = ((SharePopupInterface) getActivity()).getSharePopup();
    mAdapter = new StoryAdapter(getActivity(), mList, popup, mCallbacks, mTabletLayout);
    mList.setAdapter(mAdapter);
  }

  /**
   * inflate & add the more button *
   */
  private void setupMoreButton() {
    LayoutInflater inflater = getActivity().getLayoutInflater();
    mMoreButton = inflater.inflate(R.layout.vw_more_link, mList, false);
    mMoreButton.setOnClickListener(mMoreListener);
    mMoreButton.setVisibility(View.GONE);
    mMoreButtonText = (TextView) mMoreButton.findViewById(R.id.txt_more);
    mMoreIcon = (IconView) mMoreButton.findViewById(R.id.icv_more);
    mList.addFooterView(mMoreButton, null, false);
  }

  private void findViews() {
    // have to make sure we're finding views inside of stories container (fixes bug where StoryFragment &
    // CommentsFragment would confused each other's views when they're part of the same activity)
    View container = getActivity().findViewById(R.id.cnt_stories);

    mLoading = container.findViewById(R.id.cnt_loading);
    mError = container.findViewById(R.id.cnt_error);
    mError.findViewById(R.id.btn_error)
            .setOnClickListener(mRefreshListener);

    ViewUtils.fixBackgroundRepeat(mError);
    ViewUtils.fixBackgroundRepeat(mLoading);

    mList = (ListView) container.findViewById(android.R.id.list);
    mList.setItemsCanFocus(true);
  }

  @Override
  public void onSaveInstanceState(Bundle outState) {
    /* Save to bundle */
    if (!mCallbacks.storyFragmentIsInLayout()) {
      return;
    }
    outState.putSerializable(PAGE, mPage);
    outState = mAdapter.onSaveInstanceState(outState);
    super.onSaveInstanceState(outState);

  }

  // Loader interface
  @Override
  public Loader<StoryResponse> onCreateLoader(int id, Bundle args) {
    mIsLoading = true;
    refreshContentVisibility();
    return new StoryLoader(getActivity(), mPage, mRequest);
  }

  @Override
  public void onLoadFinished(Loader<StoryResponse> loader, StoryResponse response) {

    // if this Fragment is no longer part of the layout, return early
    if (!mCallbacks.storyFragmentIsInLayout()) {
      return;
    }

    mIsLoading = false;
    mPullToRefresh.setRefreshComplete();
    mLastResult = response.result;

    switch (mLastResult) {

      case SUCCESS: // first page
        mAdapter.clear();
        mAdapter.addAll(response.stories);
        break;

      case MORE: // new data from web
        mAdapter.addAll(response.stories);
        break;

      case FNID_EXPIRED: // the link was expired - refresh the page
        Toast.makeText(getActivity(), getActivity().getString(R.string.link_expired), Toast.LENGTH_SHORT)
                .show();
        // start loader with refresh request
        mRequest = Request.REFRESH;
        getLoaderManager().restartLoader(0, null, this);
        break;

      case FAILURE: // Show error message
        Toast.makeText(getActivity(), getActivity().getString(R.string.problem_downloading_content), Toast.LENGTH_SHORT)
                .show();
        break;

      default:
        break;
    }

    mAdapter.setActiveStory(mCallbacks.getActiveStory());
    showMoreLink();
    checkForExpiredCache(response);
    refreshContentVisibility();

    if (mShouldRestoreListState) {
      mShouldRestoreListState = false;
      int listPosition = new AppData(getActivity()).getStoryListPosition();
      mList.setSelectionFromTop(listPosition, 0);
    }
  }

  private void checkForExpiredCache(StoryResponse response) {
    if (response.timestamp != null && response.timestamp.time > Comment.JAN_1_2012
            && System.currentTimeMillis() - response.timestamp.time > Comment.CACHE_EXPIRATION) {
      // still display cached values, but need to start refresh
      mRequest = Request.REFRESH;
      getLoaderManager().restartLoader(0, null, this);
    }
  }

  private void showMoreLink() {
    mMoreButtonText.setText(getActivity().getString(R.string.load_more));
    mMoreIcon.setVisibility(View.VISIBLE);
    mMoreButton.setVisibility(View.VISIBLE);
  }

  @Override
  public void onLoaderReset(Loader<StoryResponse> loader) {
    /* no op */
  }

  @Override public void onRefreshStarted(View view) {
    mRequest = Request.REFRESH;
    getLoaderManager().restartLoader(0, null, StoryFragment.this);
  }

  // ActionBar interface
  @DebugLog
  @Override
  public void setupActionBar(Context context, ActionBarController controller) {
    if (mRefreshButton == null) {
      mRefreshButton = new ActionBarButton(context);
      mRefreshButton.text(context.getString(R.string.refresh_stories))
              .icon(R.drawable.ic_action_refresh)
              .priority(Priority.HIGH)
              .onClick(mRefreshListener);
    }
    controller.addButton(mRefreshButton);
  }

  @Override
  public void cleanupActionBar(Context context, ActionBarController controller) {
    controller.removeButton(mRefreshButton);
  }

  public void setPage(Page page) {
    mPage = page;
  }

  public StoryAdapter getStoryAdapter() {
    return mAdapter;
  }


  /**
   * Determines which view (List, Error, or Loading) show be visible. *
   */
  private void refreshContentVisibility() {
    // can't refresh visibility until / unless all views are created
    if (mAdapter == null || mList == null || mError == null || mLoading == null) {
      return;
    }

    boolean errorVis = mLastResult == Result.FAILURE && mAdapter.getCount() < 1 && !mIsLoading;
    boolean loadingVis = mIsLoading && mAdapter.getCount() < 1 && !errorVis;
    boolean listVis = !(errorVis || loadingVis);

    if (mRefreshButton != null) {
      mRefreshButton.icon(errorVis ? R.drawable.ic_action_refresh_error : R.drawable.ic_action_refresh);
      mRefreshButton.showProgress(mIsLoading);
    }

    mList.setVisibility(ViewUtils.boolToVis(listVis));
    mError.setVisibility(ViewUtils.boolToVis(errorVis));
    mLoading.setVisibility(ViewUtils.boolToVis(loadingVis));
  }

  // Static creator method
  public static StoryFragment newInstance(Page page) {
    StoryFragment fragment = new StoryFragment();
    fragment.setPage(page);
    return fragment;
  }

  @Override public boolean onNavigationItemSelected(int itemPosition, long itemId) {
    Page page = Page.fromListPosition(itemPosition);
    setPage(page);
    getLoaderManager().restartLoader(0, null, this);
    mPullToRefresh.setRefreshing(true);
    return true;
  }


  // Callbacks

  /**
   * To be implemented by any Activity containing this Fragment. Notifies when this fragment receives new data. *
   */
  public interface Callbacks {

    /**
     * Called when the User has clicked on a Story in the ListView (called by StoryAdapter).
     */
    public void showCommentsForStory(Story story, MainActivity.CommentsTab initalTab);

    /**
     * Called by StoryFragment.onLoadFinished to make sure this Fragment's StoryAdapter has the active story *
     */
    public Story getActiveStory();

    /**
     * Allows the StoryFragment to check if it is part of the layout in MainActivity after being recreated on
     * orientation change. If it is not in layout, it's method's should return early.
     */
    public boolean storyFragmentIsInLayout();
  }

}
