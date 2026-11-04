package com.simonrowe.factory.pageread.workflow;

import com.simonrowe.factory.pageread.domain.PageReadInput;
import com.simonrowe.factory.pageread.domain.PageReadResult;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * The page-reader container's one activity. There is no Java implementation: this interface
 * exists so the workflow can schedule it with a typed stub.
 *
 * <p>The name is given explicitly because Java's default activity type name is the method name
 * capitalised ({@code ReadPage}), while the Node worker registers the function under its own
 * name, {@code readPage}. Without it every read would wait on an activity type nobody polls.
 */
@ActivityInterface
public interface PageReadActivities {

  @ActivityMethod(name = "readPage")
  PageReadResult readPage(PageReadInput input);
}
