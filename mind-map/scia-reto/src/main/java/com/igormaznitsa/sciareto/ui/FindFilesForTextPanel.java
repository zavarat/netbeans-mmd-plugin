/*
 * Copyright 2017 Igor Maznitsa.
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
package com.igormaznitsa.sciareto.ui;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.charset.Charset;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.swing.ComboBoxModel;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JOptionPane;
import javax.swing.ListModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;
import com.igormaznitsa.meta.annotation.MustNotContainNull;
import com.igormaznitsa.mindmap.model.logger.Logger;
import com.igormaznitsa.mindmap.model.logger.LoggerFactory;
import com.igormaznitsa.sciareto.Context;
import com.igormaznitsa.sciareto.ui.misc.NodeListRenderer;
import com.igormaznitsa.sciareto.ui.tree.NodeFileOrFolder;

public class FindFilesForTextPanel extends javax.swing.JPanel {

  private static final long serialVersionUID = 8076096265342142731L;

  private static final Logger LOGGER = LoggerFactory.getLogger(FindFilesForTextPanel.class);

  private final AtomicReference<Thread> searchingThread = new AtomicReference<>();
  private final transient List<NodeFileOrFolder> foundFiles = new ArrayList<>();
  private final transient List<ListDataListener> listListeners = new ArrayList<>();

  private static final int MIN_TEXT_LENGTH = 3;

  private final NodeFileOrFolder folder;

  private static volatile String CHARSET = "UTF-8"; 
  
  public FindFilesForTextPanel(@Nonnull final Context context, @Nonnull final NodeFileOrFolder itemToFind) {
    initComponents();
    this.folder = itemToFind;

    this.fieldText.setText("");
    this.buttonFind.setEnabled(false);

    this.fieldText.getDocument().addDocumentListener(new DocumentListener() {
      @Override
      public void insertUpdate(DocumentEvent e) {
        updateStateForText();
      }

      @Override
      public void removeUpdate(DocumentEvent e) {
        updateStateForText();
      }

      @Override
      public void changedUpdate(DocumentEvent e) {
        updateStateForText();
      }
    });

    this.fieldText.addKeyListener(new KeyAdapter() {
      @Override
      public void keyPressed(final KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.VK_ENTER) {
          e.consume();
          if (fieldText.getText().length() >= MIN_TEXT_LENGTH) {
            buttonFind.doClick();
          }
        }
      }

    });

    this.listOfFoundElements.setCellRenderer(new NodeListRenderer());
    this.listOfFoundElements.setModel(new ListModel<NodeFileOrFolder>() {
      @Override
      public int getSize() {
        return foundFiles.size();
      }

      @Override
      @Nonnull
      public NodeFileOrFolder getElementAt(final int index) {
        return foundFiles.get(index);
      }

      @Override
      public void addListDataListener(@Nonnull final ListDataListener l) {
        listListeners.add(l);
      }

      @Override
      public void removeListDataListener(@Nonnull final ListDataListener l) {
        listListeners.remove(l);
      }

    });

    final ComboBoxModel<String> charsets = new DefaultComboBoxModel<>(Charset.availableCharsets().keySet().toArray(new String[0]));
    this.comboCharsets.setModel(charsets);
    this.comboCharsets.setSelectedItem(CHARSET);
    this.comboCharsets.addActionListener(new ActionListener() {
      @Override
      public void actionPerformed(ActionEvent e) {
        CHARSET = comboCharsets.getSelectedItem().toString();
      }
    });

    new Focuser(this.fieldText);    
    UiUtils.makeOwningDialogResizable(this);
  }

  private void updateStateForText() {
    final String text = this.fieldText.getText();
    if (text.length() >= MIN_TEXT_LENGTH) {
      this.buttonFind.setEnabled(true);
    } else {
      this.buttonFind.setEnabled(false);
    }
  }

  @Nullable
  public NodeFileOrFolder getSelected() {
    return null;
  }

  public void dispose() {
    final Thread thread = this.searchingThread.getAndSet(null);
    if (thread != null) {
      thread.interrupt();
    }
  }

  private void addFileIntoList(@Nonnull final NodeFileOrFolder file) {
    SwingUtilities.invokeLater(new Runnable() {
      @Override
      public void run() {
        final boolean first = foundFiles.isEmpty();

        foundFiles.add(file);
        for (final ListDataListener l : listListeners) {
          l.intervalAdded(new ListDataEvent(listOfFoundElements, ListDataEvent.INTERVAL_ADDED, foundFiles.size() - 1, foundFiles.size() - 1));
        }

        if (first) {
          listOfFoundElements.setSelectedIndex(0);
        }

      }
    });
  }

  private static boolean doesContain(@Nonnull final byte[] tosearch, @Nonnull final java.nio.file.Path file) throws IOException {
    final int MAPSIZE = 4 * 1024; // 4K - make this * 1024 to 4MB in a real system.

    try (final FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
      final long length = channel.size();
      int pos = 0;

      while (pos < length) {
        long remaining = length - pos;

        int trymap = MAPSIZE + tosearch.length;
        int tomap = (int) Math.min(trymap, remaining);
        int limit = trymap == tomap ? MAPSIZE : (tomap - tosearch.length);

        final MappedByteBuffer buffer = channel.map(MapMode.READ_ONLY, pos, tomap);
        pos += (trymap == tomap) ? MAPSIZE : tomap;

        for (int i = 0; i < limit; i++) {
          final byte b = buffer.get(i);
        }
      }
    }
    return false;
  }

  private void startSearchThread(@Nonnull @MustNotContainNull final List<NodeFileOrFolder> scope, @Nonnull final byte[] dataToFind) {
    int size = 0;
    for (final NodeFileOrFolder p : scope) {
      size += p.size();
    }

    final Runnable runnable = new Runnable() {
      int value = 0;
      private void processFile(final NodeFileOrFolder file) {
        value++;
        final File f = file.makeFileForNode();
        try {
          if (f != null && doesContain(dataToFind, f.toPath())) {
            addFileIntoList(file);
          }
        }
        catch (IOException ex) {
          LOGGER.error("Error during text search '" + f + "'", ex);
        }

        if (!Thread.currentThread().isInterrupted()) {
          safeSetProgressValue(value);
        }
      }

      private void processFolder(final NodeFileOrFolder folder) {
        value++;
        for (final NodeFileOrFolder f : folder) {
          if (f.isLeaf()) {
            processFile(f);
          } else {
            processFolder(f);
          }
        }
        if (!Thread.currentThread().isInterrupted()) {
          safeSetProgressValue(value);
        }
      }

      @Override
      public void run() {
        for (final NodeFileOrFolder p : scope) {
          for (final NodeFileOrFolder f : p) {
            if (Thread.currentThread().isInterrupted()) {
              return;
            }
            if (f.isLeaf()) {
              processFile(f);
            } else {
              processFolder(f);
            }
          }
        }
        safeSetProgressValue(Integer.MAX_VALUE);
        SwingUtilities.invokeLater(new Runnable() {
          @Override
          public void run() {
            buttonFind.setEnabled(true);
            fieldText.setEnabled(true);
            comboCharsets.setEnabled(true);
            fieldText.requestFocus();
          }
        });
      }
    };

    final Thread thread = new Thread(runnable, "SciaRetoSearchUsage"); //NOI18N
    thread.setDaemon(true);

    final Thread oldThread = this.searchingThread.getAndSet(thread);
    if (oldThread != null) {
      oldThread.interrupt();
      try {
        oldThread.join(1000L);
      }
      catch (InterruptedException ex) {
        LOGGER.error("Exception during waiting of search thread interruption", ex); //NOI18N
      }
    }

    this.progressBarSearch.setMinimum(0);
    this.progressBarSearch.setMaximum(size);
    this.progressBarSearch.setValue(0);

    thread.start();

  }

  private void safeSetProgressValue(final int value) {
    SwingUtilities.invokeLater(new Runnable() {
      @Override
      public void run() {
        if (value == Integer.MAX_VALUE) {
          progressBarSearch.setEnabled(false);
          progressBarSearch.setIndeterminate(false);
          progressBarSearch.setValue(progressBarSearch.getMaximum());
        } else if (value < 0) {
          progressBarSearch.setEnabled(true);
          progressBarSearch.setIndeterminate(true);
        } else {
          progressBarSearch.setEnabled(true);
          progressBarSearch.setIndeterminate(false);
          progressBarSearch.setValue(value);
        }
      }
    });
  }

  /**
   * This method is called from within the constructor to initialize the form.
   * WARNING: Do NOT modify this code. The content of this method is always
   * regenerated by the Form Editor.
   */
  @SuppressWarnings("unchecked")
  // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
  private void initComponents() {
    java.awt.GridBagConstraints gridBagConstraints;

    jPanel1 = new javax.swing.JPanel();
    jLabel1 = new javax.swing.JLabel();
    fieldText = new javax.swing.JTextField();
    buttonFind = new javax.swing.JButton();
    jPanel3 = new javax.swing.JPanel();
    filler1 = new javax.swing.Box.Filler(new java.awt.Dimension(0, 0), new java.awt.Dimension(0, 0), new java.awt.Dimension(32767, 0));
    jLabel2 = new javax.swing.JLabel();
    comboCharsets = new javax.swing.JComboBox();
    jPanel2 = new javax.swing.JPanel();
    progressBarSearch = new javax.swing.JProgressBar();
    jScrollPane1 = new javax.swing.JScrollPane();
    listOfFoundElements = new javax.swing.JList<>();

    setPreferredSize(new java.awt.Dimension(450, 450));
    setLayout(new java.awt.BorderLayout());

    jPanel1.setLayout(new java.awt.BorderLayout());

    jLabel1.setText("Text to search: ");
    jPanel1.add(jLabel1, java.awt.BorderLayout.WEST);

    fieldText.setText("jTextField1");
    jPanel1.add(fieldText, java.awt.BorderLayout.CENTER);

    buttonFind.setIcon(new javax.swing.ImageIcon(getClass().getResource("/icons/find16.png"))); // NOI18N
    buttonFind.addActionListener(new java.awt.event.ActionListener() {
      public void actionPerformed(java.awt.event.ActionEvent evt) {
        buttonFindActionPerformed(evt);
      }
    });
    jPanel1.add(buttonFind, java.awt.BorderLayout.LINE_END);

    jPanel3.setBorder(javax.swing.BorderFactory.createEmptyBorder(3, 1, 3, 1));
    jPanel3.setLayout(new java.awt.GridBagLayout());
    gridBagConstraints = new java.awt.GridBagConstraints();
    gridBagConstraints.gridx = 2;
    gridBagConstraints.gridy = 0;
    gridBagConstraints.weightx = 1000.0;
    jPanel3.add(filler1, gridBagConstraints);

    jLabel2.setText("Charset: ");
    gridBagConstraints = new java.awt.GridBagConstraints();
    gridBagConstraints.gridx = 0;
    gridBagConstraints.gridy = 0;
    jPanel3.add(jLabel2, gridBagConstraints);

    comboCharsets.setModel(new javax.swing.DefaultComboBoxModel(new String[] { "Item 1", "Item 2", "Item 3", "Item 4" }));
    gridBagConstraints = new java.awt.GridBagConstraints();
    gridBagConstraints.gridx = 1;
    gridBagConstraints.gridy = 0;
    jPanel3.add(comboCharsets, gridBagConstraints);

    jPanel1.add(jPanel3, java.awt.BorderLayout.PAGE_END);

    add(jPanel1, java.awt.BorderLayout.PAGE_START);

    jPanel2.setLayout(new java.awt.BorderLayout());
    jPanel2.add(progressBarSearch, java.awt.BorderLayout.NORTH);

    listOfFoundElements.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
    jScrollPane1.setViewportView(listOfFoundElements);

    jPanel2.add(jScrollPane1, java.awt.BorderLayout.CENTER);

    add(jPanel2, java.awt.BorderLayout.CENTER);
  }// </editor-fold>//GEN-END:initComponents

  private void buttonFindActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_buttonFindActionPerformed
    this.fieldText.setEnabled(false);
    this.buttonFind.setEnabled(false);
    this.comboCharsets.setEnabled(false);

    this.listOfFoundElements.clearSelection();
    
    final List<NodeFileOrFolder> folders = new ArrayList<>();
    folders.add(this.folder);

    try {
      startSearchThread(folders, this.fieldText.getText().getBytes(this.comboCharsets.getSelectedItem().toString()));
    }
    catch (UnsupportedEncodingException ex) {
      JOptionPane.showMessageDialog(this, ex, "Error", JOptionPane.ERROR_MESSAGE);
    }
  }//GEN-LAST:event_buttonFindActionPerformed


  // Variables declaration - do not modify//GEN-BEGIN:variables
  private javax.swing.JButton buttonFind;
  private javax.swing.JComboBox comboCharsets;
  private javax.swing.JTextField fieldText;
  private javax.swing.Box.Filler filler1;
  private javax.swing.JLabel jLabel1;
  private javax.swing.JLabel jLabel2;
  private javax.swing.JPanel jPanel1;
  private javax.swing.JPanel jPanel2;
  private javax.swing.JPanel jPanel3;
  private javax.swing.JScrollPane jScrollPane1;
  private javax.swing.JList<NodeFileOrFolder> listOfFoundElements;
  private javax.swing.JProgressBar progressBarSearch;
  // End of variables declaration//GEN-END:variables
}