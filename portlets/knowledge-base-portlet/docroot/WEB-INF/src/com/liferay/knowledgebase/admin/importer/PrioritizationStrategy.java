/**
 * Copyright (c) 2000-present Liferay, Inc. All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 */

package com.liferay.knowledgebase.admin.importer;

import com.liferay.knowledgebase.model.KBArticle;
import com.liferay.knowledgebase.model.KBArticleConstants;
import com.liferay.knowledgebase.service.KBArticleLocalServiceUtil;
import com.liferay.knowledgebase.service.KBArticleServiceUtil;
import com.liferay.portal.kernel.dao.orm.QueryUtil;
import com.liferay.portal.kernel.exception.PortalException;
import com.liferay.portal.kernel.exception.SystemException;
import com.liferay.portal.kernel.util.CharPool;
import com.liferay.portal.kernel.util.ListUtil;
import com.liferay.portal.kernel.util.StringPool;
import com.liferay.portal.kernel.util.StringUtil;
import com.liferay.portal.kernel.util.Validator;
import com.liferay.portal.kernel.workflow.WorkflowConstants;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @author Jesse Rao
 */
public class PrioritizationStrategy {

	public static PrioritizationStrategy create(
		long groupId, long parentKBFolderId, boolean prioritizeUpdatedArticles,
		boolean prioritizeByNumericalPrefix) throws SystemException {

		List<KBArticle> existingParentArticles =
			KBArticleServiceUtil.getKBArticles(
				groupId, parentKBFolderId, WorkflowConstants.STATUS_ANY,
				QueryUtil.ALL_POS, QueryUtil.ALL_POS, null);

		List<String> existingParentUrlTitles = new ArrayList<String>();

		Map<String, List<KBArticle>> existingChildArticlesMap =
				new HashMap<String, List<KBArticle>>();

		Map<String, List<String>> existingChildUrlTitlesMap =
			new HashMap<String, List<String>>();

		for (KBArticle existingParentArticle : existingParentArticles) {
			existingParentUrlTitles.add(existingParentArticle.getUrlTitle());

			long resourcePrimKey = existingParentArticle.getResourcePrimKey();

			List<KBArticle> existingChildArticles =
				KBArticleServiceUtil.getKBArticles(
					groupId, resourcePrimKey, WorkflowConstants.STATUS_ANY,
					QueryUtil.ALL_POS, QueryUtil.ALL_POS, null);

			List<String> existingChildUrlTitles = new ArrayList<String>();

			for (KBArticle existingChildArticle : existingChildArticles) {
				existingChildUrlTitles.add(existingChildArticle.getUrlTitle());
			}

			existingChildArticlesMap.put(
				existingParentArticle.getUrlTitle(), existingChildArticles);

			existingChildUrlTitlesMap.put(
				existingParentArticle.getUrlTitle(), existingChildUrlTitles);
		}

		return new PrioritizationStrategy(
			groupId, parentKBFolderId, prioritizeUpdatedArticles,
			prioritizeByNumericalPrefix, existingParentArticles,
			existingParentUrlTitles, existingChildArticlesMap,
			existingChildUrlTitlesMap);
	}

	public void addImportedChildArticle(KBArticle childArticle, String fileName)
		throws PortalException, SystemException {

		if (_prioritizeByNumericalPrefix) {
			double sectionFileEntryNamePrefix = _getNumericalPrefix(fileName);

			if (sectionFileEntryNamePrefix > 0) {
				_importedUrlTitlesPrioritiesMap.put(
					childArticle.getUrlTitle(), sectionFileEntryNamePrefix);
			}
		}

		KBArticle parentArticle = childArticle.getParentKBArticle();

		if (parentArticle == null) {
			return;
		}

		String parentUrlTitle = parentArticle.getUrlTitle();

		List<KBArticle> childArticles = null;

		if (_importedArticlesMap.containsKey(parentUrlTitle)) {
			childArticles = _importedArticlesMap.get(parentUrlTitle);
		}
		else {
			childArticles = new ArrayList<KBArticle>();
		}

		childArticles.add(childArticle);

		_importedArticlesMap.put(parentUrlTitle, childArticles);

		List<String> childUrlTitles = null;

		if (_importedUrlTitlesMap.containsKey(parentUrlTitle)) {
			childUrlTitles = _importedUrlTitlesMap.get(parentUrlTitle);
		}
		else {
			childUrlTitles = new ArrayList<String>();
		}

		childUrlTitles.add(childArticle.getUrlTitle());

		_importedUrlTitlesMap.put(parentUrlTitle, childUrlTitles);
	}

	public void addImportedParentArticle(
		KBArticle parentArticle, String fileName) {
		
		List<KBArticle> parentArticles =
			_importedArticlesMap.get(StringPool.BLANK);
		
		parentArticles.add(parentArticle);

		_importedArticlesMap.put(StringPool.BLANK, parentArticles);
		
		List<String> parentUrlTitles =
			_importedUrlTitlesMap.get(StringPool.BLANK);

		String parentUrlTitle = parentArticle.getUrlTitle();

		parentUrlTitles.add(parentUrlTitle);
		
		_importedUrlTitlesMap.put(StringPool.BLANK, parentUrlTitles);

		if (_prioritizeByNumericalPrefix) {
			double folderNamePrefix = _getNumericalPrefix(fileName);

			if (folderNamePrefix > 0) {
				_importedUrlTitlesPrioritiesMap.put(
					parentArticle.getUrlTitle(), folderNamePrefix);
			}
		}
	}

	public void prioritizeArticles() throws PortalException, SystemException {
		if (_prioritizeUpdatedArticles) {
			_initNonImportedArticles();
		}
		else {
			_initNewArticles();
		}

		if (_prioritizeByNumericalPrefix) {
			Set<String> importedKBArticleUrlTitles =
				_importedUrlTitlesPrioritiesMap.keySet();

			for (String importedKBArticleUrlTitle :
					importedKBArticleUrlTitles) {

				KBArticle kbArticle =
					KBArticleLocalServiceUtil.getKBArticleByUrlTitle(
						_groupId, _parentKBFolderId, importedKBArticleUrlTitle);

				double priority = _importedUrlTitlesPrioritiesMap.get(
					importedKBArticleUrlTitle);

				KBArticleLocalServiceUtil.updatePriority(
					kbArticle.getResourcePrimKey(), priority);

				/*
				 * Remove articles with numerical prefixes, and their URL
				 * titles, from lists of imported and new articles. Only
				 * articles without numerical prefixes need to be
				 * alphanumerically prioritized.
				 */

				if (_importedArticlesMap != null) {
					Set<String> keySet = _importedArticlesMap.keySet();
					
					for (String parentUrlTitle : keySet) {
						List<KBArticle> kbArticles =
							_importedArticlesMap.get(parentUrlTitle);
						
						if (kbArticles.contains(kbArticle)) {
							kbArticles.remove(kbArticle);
						}
						
						_importedArticlesMap.put(parentUrlTitle, kbArticles);
					}
					
					keySet = _importedUrlTitlesMap.keySet();
					
					for (String parentUrlTitle : keySet) {
						List<String> urlTitles =
							_importedUrlTitlesMap.get(parentUrlTitle);
						
						String urlTitle = kbArticle.getUrlTitle();
						
						if (urlTitles.contains(urlTitle)) {
							urlTitles.remove(urlTitle);
						}
						
						_importedUrlTitlesMap.put(parentUrlTitle, urlTitles);
					}
				}
				
				if (_newArticlesMap != null) {
					Set<String> keySet = _newArticlesMap.keySet();
					
					for (String parentUrlTitle : keySet) {
						List<KBArticle> kbArticles =
							_newArticlesMap.get(parentUrlTitle);
						
						if (kbArticles.contains(kbArticle)) {
							kbArticles.remove(kbArticle);
						}
						
						_newArticlesMap.put(parentUrlTitle, kbArticles);
					}
					
					keySet = _newUrlTitlesMap.keySet();
					
					for (String parentUrlTitle : keySet) {
						List<String> urlTitles =
							_newUrlTitlesMap.get(parentUrlTitle);
						
						String urlTitle = kbArticle.getUrlTitle();
						
						if (urlTitles.contains(urlTitle)) {
							urlTitles.remove(urlTitle);
						}
						
						_newUrlTitlesMap.put(parentUrlTitle, urlTitles);
					}
				}
			}
		}

		if (_prioritizeUpdatedArticles) {

			// prioritize all imported articles

			double maxParentKBArticlePriority = 0.0;

			Map<String, Double> maxChildKBArticlePriorityMap =
				new HashMap<String, Double>();

			for (KBArticle parentKBArticle : _nonImportedParentArticles) {
				double parentKBArticlePriority = parentKBArticle.getPriority();

				if (parentKBArticlePriority > maxParentKBArticlePriority) {
					maxParentKBArticlePriority = parentKBArticlePriority;
				}

				String parentKBArticleUrlTitle = parentKBArticle.getUrlTitle();

				List<KBArticle> childKBArticles =
					_nonImportedChildArticlesMap.get(parentKBArticleUrlTitle);

				if (childKBArticles == null) {
					continue;
				}

				double maxChildKBArticlePriority = 0.0;

				for (KBArticle childArticle : childKBArticles) {
					double childKBArticlePriority = childArticle.getPriority();

					if (childKBArticlePriority > maxChildKBArticlePriority) {
						maxChildKBArticlePriority = childKBArticlePriority;
					}
				}

				maxChildKBArticlePriorityMap.put(
					parentKBArticleUrlTitle, maxChildKBArticlePriority);
			}

			// prioritize imported parent articles by URL title

			ListUtil.sort(_importedParentUrlTitles);

			for (String importedParentKBArticleUrlTitle :
					_importedParentUrlTitles) {

				KBArticle parentKBArticle =
					KBArticleLocalServiceUtil.getKBArticleByUrlTitle(
						_groupId, _parentKBFolderId,
						importedParentKBArticleUrlTitle);

				maxParentKBArticlePriority++;

				KBArticleLocalServiceUtil.updatePriority(
					parentKBArticle.getResourcePrimKey(),
					maxParentKBArticlePriority);
			}

			// prioritize imported child articles by URL title

			updateChildKBArticlesPriorities(
				_importedChildArticlesMap, maxChildKBArticlePriorityMap);
		}
		else {

			// prioritize only new articles

			double maxParentKBArticlePriority = 0.0;

			Map<String, Double> maxChildKBArticlePriorityMap =
				new HashMap<String, Double>();

			for (KBArticle parentKBArticle : _existingParentArticles) {
				double parentKBArticlePriority = parentKBArticle.getPriority();

				if (parentKBArticlePriority > maxParentKBArticlePriority) {
					maxParentKBArticlePriority = parentKBArticlePriority;
				}

				String parentKBArticleUrlTitle = parentKBArticle.getUrlTitle();

				List<KBArticle> childKBArticles = _existingChildArticlesMap.get(
					parentKBArticleUrlTitle);

				double maxChildKBArticlePriority = 0.0;

				for (KBArticle childKBArticle : childKBArticles) {
					double childKBArticlePriority =
						childKBArticle.getPriority();

					if (childKBArticlePriority > maxChildKBArticlePriority) {
						maxChildKBArticlePriority = childKBArticlePriority;
					}
				}

				maxChildKBArticlePriorityMap.put(
					parentKBArticleUrlTitle, maxChildKBArticlePriority);
			}

			// prioritize new parent articles by URL title

			ListUtil.sort(_newParentUrlTitles);

			for (String parentKBArticleUrlTitle : _newParentUrlTitles) {
				KBArticle parentKBArticle =
					KBArticleLocalServiceUtil.getKBArticleByUrlTitle(
						_groupId, _parentKBFolderId, parentKBArticleUrlTitle);

				maxParentKBArticlePriority++;

				KBArticleLocalServiceUtil.updatePriority(
					parentKBArticle.getResourcePrimKey(),
					maxParentKBArticlePriority);
			}

			// prioritize new child articles by URL title

			updateChildKBArticlesPriorities(
				_newChildArticlesMap, maxChildKBArticlePriorityMap);
		}
	}

	protected PrioritizationStrategy(
		long groupId, long parentKBFolderId, boolean prioritizeUpdatedArticles,
		boolean prioritizeByNumericalPrefix,
		List<KBArticle> existingParentArticles,
		List<String> existingParentUrlTitles,
		Map<String, List<KBArticle>> existingChildArticlesMap,
		Map<String, List<String>> existingChildUrlTitlesMap) {

		_groupId = groupId;
		_parentKBFolderId = parentKBFolderId;
		
		_prioritizeUpdatedArticles = prioritizeUpdatedArticles;
		_prioritizeByNumericalPrefix = prioritizeByNumericalPrefix;
		
		_existingArticlesMap = new HashMap<String, List<KBArticle>>();
		_existingArticlesMap.put(StringPool.BLANK, existingParentArticles);
		
		Set<String> keySet = existingChildArticlesMap.keySet();
		for (String key : keySet) {
			List<KBArticle> childArticles = existingChildArticlesMap.get(key);
			
			_existingArticlesMap.put(key, childArticles);
		}
		
		_existingUrlTitlesMap = new HashMap<String, List<String>>();
		_existingUrlTitlesMap.put(StringPool.BLANK, existingParentUrlTitles);
		
		keySet = existingChildUrlTitlesMap.keySet();
		for (String key : keySet) {
			List<String> childUrlTitles = existingChildUrlTitlesMap.get(key);
			
			_existingUrlTitlesMap.put(key, childUrlTitles);
		}
		
		_importedArticlesMap = new HashMap<String, List<KBArticle>>();
		_importedUrlTitlesMap = new HashMap<String, List<String>>();
		
		_importedUrlTitlesPrioritiesMap = new HashMap<String, Double>();
	}

	protected void updateChildKBArticlesPriorities(
			Map<String, List<KBArticle>> childKBArticlesMap,
			Map<String, Double> maxChildKBArticlePriorityMap)
		throws SystemException {

		Set<String> parentKBArticleUrlTitles = childKBArticlesMap.keySet();

		for (String parentKBArticleUrlTitle : parentKBArticleUrlTitles) {
			List<KBArticle> childKBArticles = childKBArticlesMap.get(
				parentKBArticleUrlTitle);

			if (childKBArticles == null) {
				return;
			}

			Double maxChildKBArticlePriority = maxChildKBArticlePriorityMap.get(
				parentKBArticleUrlTitle);

			if (maxChildKBArticlePriority == null) {
				maxChildKBArticlePriority = 0.0;
			}

			ListUtil.sort(childKBArticles, new KBArticleComparator());

			for (KBArticle childKBArticle : childKBArticles) {
				maxChildKBArticlePriority++;

				KBArticleLocalServiceUtil.updatePriority(
					childKBArticle.getResourcePrimKey(),
					maxChildKBArticlePriority);
			}
		}
	}

	private double _getNumericalPrefix(String path) {
		int i = path.lastIndexOf(CharPool.SLASH);

		if (i == -1) {
			return KBArticleConstants.DEFAULT_PRIORITY;
		}

		String name = path.substring(i);

		String numericalPrefix = StringUtil.extractLeadingDigits(name);

		if (Validator.isNull(numericalPrefix)) {
			return KBArticleConstants.DEFAULT_PRIORITY;
		}

		return Double.parseDouble(numericalPrefix);
	}

	private void _initNewArticles() {
		_newArticlesMap = new HashMap<String, List<KBArticle>>();
		
		Set<String> keySet = _importedArticlesMap.keySet();
		
		for (String parentUrlTitle : keySet) {
			List<KBArticle> importedArticles =
				_importedArticlesMap.get(parentUrlTitle);
			
			List<String> existingUrlTitles =
				_existingUrlTitlesMap.get(parentUrlTitle);
			
			List<KBArticle> newArticles = new ArrayList<KBArticle>();
	
			for (KBArticle kbArticle : importedArticles) {
				String urlTitle = kbArticle.getUrlTitle();
	
				if (!existingUrlTitles.contains(urlTitle)) {
					newArticles.add(kbArticle);
				}
			}
			
			_newArticlesMap.put(parentUrlTitle, newArticles);
		}
		
		_newUrlTitlesMap = new HashMap<String, List<String>>();
		
		keySet = _newArticlesMap.keySet();
		
		for (String parentUrlTitle : keySet) {
			List<KBArticle> kbArticles = _newArticlesMap.get(parentUrlTitle);
			
			List<String> kbUrlTitles = new ArrayList<String>();
			
			for (KBArticle kbArticle : kbArticles) {
				kbUrlTitles.add(kbArticle.getUrlTitle());
			}
			
			_newUrlTitlesMap.put(parentUrlTitle, kbUrlTitles);
		}
	}

	private void _initNonImportedArticles() {
		_nonImportedArticlesMap = new HashMap<String, List<KBArticle>>();
		
		Set<String> keySet = _existingArticlesMap.keySet();

		for (String parentUrlTitle : keySet) {
			List<KBArticle> existingArticles =
				_existingArticlesMap.get(parentUrlTitle);
			
			List<String> importedUrlTitles =
				_importedUrlTitlesMap.get(parentUrlTitle);

			List<KBArticle> nonImportedArticles = new ArrayList<KBArticle>();
			
			for (KBArticle kbArticle : existingArticles) {
				String urlTitle = kbArticle.getUrlTitle();
	
				if (!importedUrlTitles.contains(urlTitle)) {
					nonImportedArticles.add(kbArticle);
				}
			}
			
			_nonImportedArticlesMap.put(parentUrlTitle, nonImportedArticles);
		}
		
		_nonImportedUrlTitlesMap = new HashMap<String, List<String>>();
		
		keySet = _nonImportedArticlesMap.keySet();
		
		for (String parentUrlTitle : keySet) {
			List<KBArticle> kbArticles =
				_nonImportedArticlesMap.get(parentUrlTitle);

			List<String> kbUrlTitles = new ArrayList<String>();
			
			for (KBArticle kbArticle : kbArticles) {
				kbUrlTitles.add(kbArticle.getUrlTitle());
			}
			
			_nonImportedUrlTitlesMap.put(parentUrlTitle, kbUrlTitles);
		}
	}
	
	private Map<String, List<KBArticle>> _existingArticlesMap;
	private Map<String, List<String>> _existingUrlTitlesMap;
	private final long _groupId;
	private Map<String, List<KBArticle>> _importedArticlesMap;
	private Map<String, List<String>> _importedUrlTitlesMap;
	private Map<String, Double> _importedUrlTitlesPrioritiesMap;
	private Map<String, List<KBArticle>> _newArticlesMap;
	private Map<String, List<String>> _newUrlTitlesMap;
	private Map<String, List<KBArticle>> _nonImportedArticlesMap;
	private Map<String, List<String>> _nonImportedUrlTitlesMap;
	private final long _parentKBFolderId;
	private boolean _prioritizeByNumericalPrefix;
	private boolean _prioritizeUpdatedArticles;

}