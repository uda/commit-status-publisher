package jetbrains.buildServer.commitPublisher;

import jetbrains.buildServer.serverSide.BuildPromotion;
import jetbrains.buildServer.users.User;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class AdditionalTaskInfo {
  protected final String myComment;
  protected final User myCommentAuthor;
  protected final BuildPromotion myReplacingPromotion;

  public AdditionalTaskInfo(@Nullable String comment, @Nullable User commentAuthor) {
    this(comment, commentAuthor, null);
  }

  public AdditionalTaskInfo(@Nullable String comment, @Nullable User commentAuthor, @Nullable BuildPromotion replacingPromotion) {
    myComment = comment;
    myCommentAuthor = commentAuthor;
    myReplacingPromotion = replacingPromotion;
  }

  @NotNull
  public String getComment() {
    return myComment == null ? "" : myComment;
  }

  @NotNull
  public String getCommentOrDefault(@NotNull String defaultValue) {
    return myComment == null ? defaultValue : myComment;
  }

  public boolean commentContains(@NotNull String substring) {
    return myComment != null && myComment.contains(substring);
  }

  @Nullable
  public User getCommentAuthor() {
    return myCommentAuthor;
  }

  @Nullable
  public BuildPromotion getReplacingPromotion() {
    return myReplacingPromotion;
  }

  public boolean isPromotionReplaced() {
    return myReplacingPromotion != null;
  }
}
