/*
 * Copyright 2000-2011 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package git4idea.process;

import com.intellij.notification.NotificationType;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Clock;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.openapi.vcs.history.VcsRevisionNumber;
import com.intellij.openapi.vcs.merge.MergeDialogCustomizer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.continuation.ContinuationContext;
import com.intellij.util.text.DateFormatUtil;
import com.intellij.util.ui.UIUtil;
import git4idea.Git;
import git4idea.GitVcs;
import git4idea.commands.GitCommandResult;
import git4idea.merge.GitConflictResolver;
import git4idea.repo.GitRepository;
import git4idea.stash.GitChangesSaver;
import git4idea.update.GitComplexProcess;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

import static git4idea.ui.GitUIUtil.notifyError;

/**
 * Executor of Git branching operations.
 *
 * @author Kirill Likhodedov
 */
public final class GitBranchOperationsProcessor {

  private static final Logger LOG = Logger.getInstance(GitBranchOperationsProcessor.class);

  private final Project myProject;
  private final GitRepository myRepository;
  private final VirtualFile myRoot;

  public GitBranchOperationsProcessor(@NotNull Project project, @NotNull GitRepository repository) {
    myProject = project;
    myRepository = repository;
    myRoot = myRepository.getRoot();
  }

  /**
   * Checks out a new branch in background.
   * If there are unmerged files, proposes to resolve the conflicts and tries to check out again.
   * Doesn't check the name of new branch for validity - do this before calling this method, otherwise a standard error dialog will be shown.
   *
   * @param name Name of the new branch to check out.
   */
  public void checkoutNewBranch(@NotNull final String name) {
    new CommonBackgroundTask(myProject, "Checking out new branch " + name) {
      @Override public void execute(@NotNull ProgressIndicator indicator) {
        doCheckoutNewBranch(name);
      }
    }.runInBackground();
  }

  private void doCheckoutNewBranch(@NotNull final String name) {
    GitUnmergedFilesDetector unmergedDetector = new GitUnmergedFilesDetector();
    GitCommandResult result = Git.checkoutNewBranch(myRepository, name, unmergedDetector);
    if (result.success()) {
      updateRepository();
      notifySuccess(String.format("Branch <b><code>%s</code></b> was created", name));
    } else if (unmergedDetector.isUnmergedFilesDetected()) {
      GitConflictResolver gitConflictResolver = prepareConflictResolverForUnmergedFilesBeforeCheckout();
      if (gitConflictResolver.merge()) { // try again to checkout
        doCheckoutNewBranch(name);
      }
    } else { // other error
      showErrorMessage("Couldn't create new branch " + name, result.getErrorOutput());
    }
  }

  private GitConflictResolver prepareConflictResolverForUnmergedFilesBeforeCheckout() {
    GitConflictResolver.Params params = new GitConflictResolver.Params().
      setMergeDescription("The following files have unresolved conflicts. You need to resolve them before checking out.").
      setErrorNotificationTitle("Can't create new branch");
    return new GitConflictResolver(myProject, Collections.singleton(myRoot), params);
  }

  /**
   * Checks out remote branch as a new local branch.
   * Provides the "smart checkout" procedure the same as in {@link #checkout(String)}.
   *
   * @param newBranchName     Name of new local branch.
   * @param trackedBranchName Name of the remote branch being checked out.
   */
  public void checkoutNewTrackingBranch(@NotNull String newBranchName, @NotNull String trackedBranchName) {
    commonCheckout(trackedBranchName, newBranchName);
  }

  /**
   * <p>
   *   Checks out the given reference (a branch, or a reference name, or a commit hash).
   *   If local changes prevent the checkout, shows the list of them and proposes to make a "smart checkout":
   *   stash-checkout-unstash.
   * </p>
   * <p>
   *   Doesn't check the reference for validity.
   * </p>
   *
   * @param reference reference to be checked out.
   */
  public void checkout(@NotNull final String reference) {
    commonCheckout(reference, null);
  }

  private void commonCheckout(@NotNull final String reference, @Nullable final String newTrackingBranch) {
    new CommonBackgroundTask(myProject, "Checking out " + reference) {
      @Override public void execute(@NotNull ProgressIndicator indicator) {
        doCheckout(indicator, reference, newTrackingBranch);
      }
    }.runInBackground();
  }

  private void doCheckout(@NotNull ProgressIndicator indicator, @NotNull String reference, @Nullable String newTrackingBranch) {
    final GitWouldBeOverwrittenByCheckoutDetector checkoutListener = new GitWouldBeOverwrittenByCheckoutDetector();
    GitUnmergedFilesDetector unmergedDetector = new GitUnmergedFilesDetector();

    GitCommandResult result = Git.checkout(myRepository, reference, newTrackingBranch, checkoutListener, unmergedDetector);
    if (result.success()) {
      refreshRoot();
      updateRepository();
      notifySuccess(String.format("Checked out <b><code>%s</code></b>", reference));
    }
    else if (unmergedDetector.isUnmergedFilesDetected()) {
      GitConflictResolver gitConflictResolver = prepareConflictResolverForUnmergedFilesBeforeCheckout();
      if (gitConflictResolver.merge()) { // try again to checkout
        doCheckout(indicator, reference, newTrackingBranch);
      }
    }
    else if (checkoutListener.isWouldBeOverwrittenError()) {
      List<Change> affectedChanges = getChangesAffectedByCheckout(checkoutListener.getAffectedFiles());
      if (GitWouldBeOverwrittenByCheckoutDialog.showAndGetAnswer(myProject, affectedChanges)) {
        smartCheckout(reference, newTrackingBranch, indicator);
      }
    }
    else {
      showErrorMessage("Couldn't checkout " + reference, result.getErrorOutput());
    }
  }

  // stash - checkout - unstash
  private void smartCheckout(@NotNull final String reference, @Nullable final String newTrackingBranch, @NotNull ProgressIndicator indicator) {
    final GitChangesSaver saver = configureSaver(reference, indicator);

    GitComplexProcess.Operation checkoutOperation = new GitComplexProcess.Operation() {
      @Override public void run(ContinuationContext context) {
        if (saveOrNotify(saver)) {
          try {
            checkoutOrNotify(reference, newTrackingBranch);
          } finally {
            saver.restoreLocalChanges(context);
          }
        }
      }
    };
    GitComplexProcess.execute(myProject, "checkout", checkoutOperation);
  }

  /**
   * Configures the saver, actually notifications and texts in the GitConflictResolver used inside.
   */
  private GitChangesSaver configureSaver(final String reference, ProgressIndicator indicator) {
    GitChangesSaver saver = GitChangesSaver.getSaver(myProject, indicator, String.format("Checkout %s at %s",
                                                                                         reference,
                                                                                         DateFormatUtil.formatDateTime(Clock.getTime())));
    MergeDialogCustomizer mergeDialogCustomizer = new MergeDialogCustomizer() {
      @Override
      public String getMultipleFileMergeDescription(Collection<VirtualFile> files) {
        return String.format(
          "<html>Uncommitted changes that were saved before checkout have conflicts with files from <code>%s</code></html>",
          reference);
      }

      @Override
      public String getLeftPanelTitle(VirtualFile file) {
        return "Uncommitted changes";
      }

      @Override
      public String getRightPanelTitle(VirtualFile file, VcsRevisionNumber lastRevisionNumber) {
        return String.format("<html>Changes from <b><code>%s</code></b></html>", reference);
      }
    };

    GitConflictResolver.Params params = new GitConflictResolver.Params().
      setReverse(true).
      setMergeDialogCustomizer(mergeDialogCustomizer).
      setErrorNotificationTitle("Local changes were not restored");

    saver.setConflictResolverParams(params);
    return saver;
  }

  /**
   * Saves local changes. In case of error shows a notification and returns false.
   */
  private boolean saveOrNotify(GitChangesSaver saver) {
    try {
      saver.saveLocalChanges(Collections.singleton(myRoot));
      return true;
    } catch (VcsException e) {
      LOG.info("Couldn't save local changes", e);
      notifyError(myProject, "Git checkout failed",
                  "Tried to save uncommitted changes in " + saver.getSaverName() + " before checkout, but failed with an error.<br/>" +
                  "Update was cancelled.", true, e);
      return false;
    }
  }

  /**
   * Checks out or shows an error message.
   */
  private boolean checkoutOrNotify(@NotNull String reference, @Nullable String newTrackingBranch) {
    GitCommandResult checkoutResult = Git.checkout(myRepository, reference, newTrackingBranch);
    if (checkoutResult.success()) {
      return true;
    }
    else {
      showErrorMessage("Couldn't checkout " + reference, checkoutResult.getErrorOutput());
      return false;
    }
  }

  /**
   * Forms the list of the changes, that would be overwritten by checkout.
   * @param affectedRelativePaths paths returned by Git.
   * @return List of Changes is these paths.
   */
  private List<Change> getChangesAffectedByCheckout(Set<String> affectedRelativePaths) {
    ChangeListManager changeListManager = ChangeListManager.getInstance(myProject);
    List<Change> affectedChanges = new ArrayList<Change>();
    for (String relPath : affectedRelativePaths) {
      VirtualFile file = myRepository.getRoot().findFileByRelativePath(FileUtil.toSystemIndependentName(relPath));
      if (file != null) {
        Change change = changeListManager.getChange(file);
        if (change != null) {
          affectedChanges.add(change);
        }
      }
    }
    return affectedChanges;
  }

  private void refreshRoot() {
    myRepository.getRoot().refresh(true, true);
  }

  public void deleteBranch(final String branchName) {
    Task.Backgroundable task = new Task.Backgroundable(myProject, "Deleting " + branchName) {
      @Override public void run(@NotNull ProgressIndicator indicator) {
        GitCommandResult result = Git.branchDelete(myRepository, branchName);
        if (result.success()) {
          updateRepository();
          notifySuccess(String.format("Deleted branch <b><code>%s</code></b>", branchName));
        } else {
          showErrorMessage("Couldn't delete " + branchName, result.getErrorOutput());
        }
      }
    };
    GitVcs.runInBackground(task);
  }

  private void updateRepository() {
    myRepository.update(GitRepository.TrackedTopic.CURRENT_BRANCH, GitRepository.TrackedTopic.BRANCHES);
  }
  
  private void showErrorMessage(@NotNull final String message, @NotNull final List<String> errorOutput) {
    UIUtil.invokeAndWaitIfNeeded(new Runnable() {
      @Override
      public void run() {
        Messages.showErrorDialog(myProject, StringUtil.join(errorOutput, "\n"), message);
      }
    });
  }
  
  private void notifySuccess(String message) {
    GitVcs.NOTIFICATION_GROUP_ID.createNotification(message, NotificationType.INFORMATION).notify(myProject);
  }

  /**
   * Executes common operations before/after executing the actual branch operation.
   */
  private static abstract class CommonBackgroundTask extends Task.Backgroundable {

    private CommonBackgroundTask(@Nullable final Project project, @NotNull final String title) {
      super(project, title);
    }

    @Override
    public final void run(@NotNull ProgressIndicator indicator) {
      saveAllDocuments();
      execute(indicator);
    }

    abstract void execute(@NotNull ProgressIndicator indicator);

    void runInBackground() {
      GitVcs.runInBackground(this);
    }

    private static void saveAllDocuments() {
      UIUtil.invokeAndWaitIfNeeded(new Runnable() {
        @Override
        public void run() {
          FileDocumentManager.getInstance().saveAllDocuments();
        }
      });
    }
  }

}
