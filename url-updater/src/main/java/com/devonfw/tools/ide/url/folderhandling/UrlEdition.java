package com.devonfw.tools.ide.url.folderhandling;

import com.devonfw.tools.ide.url.folderhandling.abstractUrlClasses.AbstractUrlFolderWithParent;

/**
 * An {@link UrlFolder} representing the actual edition of a {@link UrlTool}. The default edition may have the same
 * {@link #getName() name} as the {@link UrlTool} itself. However, tools like "intellij" may have editions like
 * "community" or "ultimate".
 */
public class UrlEdition extends AbstractUrlFolderWithParent<UrlTool, UrlVersion> {

	/**
	 * The constructor.
	 *
	 * @param parent the {@link #getParent() parent folder}.
	 * @param name   the {@link #getName() filename}.
	 */
	public UrlEdition(UrlTool parent, String name) {

		super(parent, name);
	}

	/**
	 * This method is used to add new children to the children collection of an instance from this class.
	 *
	 * @param name The name of the {@link UrlVersion} object that should be created.
	 */
	@Override
	protected UrlVersion newChild(String name) {

		return new UrlVersion(this, name);
	}

}