/*
 * Copyright 2000-2015 JetBrains s.r.o.
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
package com.intellij.xdebugger.impl.ui.tree.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.AppUIUtil;
import com.intellij.util.SmartList;
import com.intellij.xdebugger.frame.XFullValueEvaluator;
import com.intellij.xdebugger.impl.ui.DebuggerUIUtil;
import com.intellij.xdebugger.impl.ui.tree.XDebuggerTree;
import com.intellij.xdebugger.impl.ui.tree.nodes.HeadlessValueEvaluationCallback;
import com.intellij.xdebugger.impl.ui.tree.nodes.WatchMessageNode;
import com.intellij.xdebugger.impl.ui.tree.nodes.XValueNodeImpl;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.tree.TreePath;
import java.util.List;

public abstract class XFetchValueActionBase extends AnAction {
  @Nullable
  private static TreePath[] getSelectedNodes(DataContext dataContext) {
    XDebuggerTree tree = XDebuggerTree.getTree(dataContext);
    return tree == null ? null : tree.getSelectionPaths();
  }

  @Override
  public void update(@NotNull AnActionEvent e) {
    TreePath[] paths = getSelectedNodes(e.getDataContext());
    if (paths != null) {
      for (TreePath path : paths) {
        Object node = path.getLastPathComponent();
        if (node instanceof XValueNodeImpl) {
          if (((XValueNodeImpl)node).isComputed()) {
            e.getPresentation().setEnabled(true);
            return;
          }
        }
        else if (node instanceof WatchMessageNode) {
          e.getPresentation().setEnabled(true);
          return;
        }
      }
    }
    e.getPresentation().setEnabled(false);
  }

  @Override
  public void actionPerformed(@NotNull final AnActionEvent e) {
    TreePath[] paths = getSelectedNodes(e.getDataContext());
    if (paths == null) {
      return;
    }

    ValueCollector valueCollector = createCollector(e);
    for (TreePath path : paths) {
      Object node = path.getLastPathComponent();
      if (node instanceof XValueNodeImpl) {
        XValueNodeImpl valueNode = (XValueNodeImpl)node;
        XFullValueEvaluator fullValueEvaluator = valueNode.getFullValueEvaluator();
        if (paths.length > 1) { // multiselection - copy the whole node text, see IDEA-136722
          valueCollector.add(valueNode.getText().toString(), valueNode.getPath().getPathCount());
        }
        else {
          if (fullValueEvaluator == null || !fullValueEvaluator.isShowValuePopup()) {
            valueCollector.add(StringUtil.notNullize(DebuggerUIUtil.getNodeRawValue(valueNode)));
          }
          else {
            new CopyValueEvaluationCallback(valueNode, valueCollector).startFetchingValue(fullValueEvaluator);
          }
        }
      }
      else if (node instanceof WatchMessageNode) {
        valueCollector.add(((WatchMessageNode)node).getExpression().getExpression());
      }
    }
    valueCollector.processed = true;
    valueCollector.finish(e.getProject());
  }

  @NotNull
  protected ValueCollector createCollector(@NotNull AnActionEvent e) {
    return new ValueCollector(XDebuggerTree.getTree(e.getDataContext()));
  }

  protected class ValueCollector {
    private final List<String> values = new SmartList<String>();
    private final List<Integer> indents = new SmartList<Integer>();
    private final XDebuggerTree myTree;
    private volatile boolean processed;

    public ValueCollector(XDebuggerTree tree) {
      myTree = tree;
    }

    public void add(@NotNull String value) {
      add(value, 0);
    }

    public void add(@NotNull String value, int indent) {
      values.add(value);
      indents.add(indent);
    }

    public void finish(Project project) {
      if (processed && !values.contains(null) && !project.isDisposed()) {
        int minIndent = Integer.MAX_VALUE;
        for (Integer indent : indents) {
          minIndent = Math.min(minIndent, indent);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
          if (i > 0) {
            sb.append("\n");
          }
          Integer indent = indents.get(i);
          StringUtil.repeatSymbol(sb, ' ', indent - minIndent);
          sb.append(values.get(i));
        }
        handleInCollector(project, sb.toString(), myTree);
      }
    }

    public void handleInCollector(final Project project, final String value, XDebuggerTree tree) {
      handle(project, value, tree);
    }

    public int acquire() {
      int index = values.size();
      values.add(null);
      return index;
    }

    public void evaluationComplete(final int index, @NotNull final String value, final Project project) {
      AppUIUtil.invokeOnEdt(new Runnable() {
        @Override
        public void run() {
          values.set(index, value);
          finish(project);
        }
      });
    }
  }

  protected abstract void handle(final Project project, final String value, XDebuggerTree tree);

  private static final class CopyValueEvaluationCallback extends HeadlessValueEvaluationCallback {
    private final int myValueIndex;
    private final ValueCollector myValueCollector;

    public CopyValueEvaluationCallback(@NotNull XValueNodeImpl node, @NotNull ValueCollector valueCollector) {
      super(node);

      myValueCollector = valueCollector;
      myValueIndex = valueCollector.acquire();
    }

    @Override
    protected void evaluationComplete(@NotNull String value, @NotNull Project project) {
      myValueCollector.evaluationComplete(myValueIndex, value, project);
    }
  }
}
