package cronapi.database;

import java.lang.reflect.Method;
import java.util.List;

import javax.persistence.EntityManager;
import javax.persistence.TypedQuery;

import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.support.Repositories;

import cronapi.Utils;
import cronapi.i18n.Messages;

/**
 * Class database manipulation, responsible for querying, inserting, 
 * updating and deleting database data procedurally, allowing paged 
 * navigation and setting page size.
 * 
 * @author robson.ataide
 * @version 1.0
 * @since 2017-04-26
 *
 */
public class DataSource {

	private String entity;
	private Class domainClass;
	private String filter;
	private Object[][] params;
	private int pageSize;
	private Page page;
	private int index;
	private int current;
	private JpaRepository<Object, String> repository;
	private Pageable pageRequest;
	private Object insertedElement = null;

	/** 
	 * Init a datasource with a page size equals 50
	 * 
	 * @param entity - full name of entitiy class like String  
	 */
	public DataSource(String entity) {
		this(entity, 100);
	}

	/** 
	 * Init a datasource setting a page size
	* 
	* @param entity - full name of entitiy class like String 
	* @param pageSize - page size of a Pageable object retrieved from repository  
	*/
	public DataSource(String entity, int pageSize) {
		this.entity = entity;
		this.pageSize = pageSize;
		this.pageRequest = new PageRequest(0, pageSize);

		//initialize dependencies and necessaries objects
		this.instantiateRepository();
	}

	/** 
	 * Retrieve repository from entity
	 * 
	 * @throws RuntimeException when repository not fount, entity passed not found or cast repository 
	 */
	private void instantiateRepository() {
		try {
			ListableBeanFactory factory = (ListableBeanFactory) ApplicationContextHolder.getContext();
			Repositories repositories = new Repositories(factory);
			domainClass = Class.forName(this.entity);
			if (repositories.hasRepositoryFor(domainClass)) {
				this.repository = (JpaRepository) repositories.getRepositoryFor(domainClass);
			} else {
				throw new RuntimeException(new ClassNotFoundException(
						Messages.format(Messages.getString("REPOSITORY_NOT_FOUND"), this.entity)));
			}
		} catch (ClassNotFoundException cnfex) {
			throw new RuntimeException(cnfex);
		} catch (ClassCastException ccex) {
			throw new RuntimeException(ccex);
		}
	}

	/**
	 * Retrieve objects from database using repository when filter is null or empty,
	 * if filter not null or is not empty, this method uses entityManager and create a
	 * jpql instruction.
	 * 
	 * @return a array of Object  
	 */
	public Object[] fetch() {
		if (this.filter != null && !"".equals(this.filter)) {
			try {
				RepositoryUtil ru = (RepositoryUtil) ApplicationContextHolder.getContext().getBean("repositoryUtil");
				EntityManager em = ru.getEntityManager(domainClass);
				TypedQuery<?> queryCount;
				TypedQuery<?> query = em.createQuery(filter, domainClass);

				String selectCount = "Select COUNT( %s ) from ";
				String[] parts = filter.split("(?i)from");
				String[] aliasParts = parts[1].split("(?i)where")[0].split(" ");
				String alias = aliasParts[aliasParts.length - 1];
				String filterCount = String.format(selectCount, alias) + parts[1];

				if (filterCount != null) {
					queryCount = em.createQuery(filterCount, Long.class);
				} else
					queryCount = query;

				for (Object[] p : this.params) {
					query.setParameter(p[0].toString(), p[1]);
					queryCount.setParameter(p[0].toString(), p[1]);
				}

				long totalResults = 0;

				if (filterCount != null)
					totalResults = (long) queryCount.getSingleResult();
				else
					totalResults = queryCount.getResultList().size();

				query.setFirstResult(this.pageRequest.getPageNumber() * this.pageRequest.getPageSize());
				query.setMaxResults(this.pageRequest.getPageSize());

				List<?> resultsInPage = query.getResultList();

				this.page = new PageImpl(resultsInPage, this.pageRequest, totalResults);
			} catch (Exception ex) {
				throw new RuntimeException(Messages.format(Messages.getString("DATASOURCE_INVALID_QUERY"), filter));
			}
		} else
			this.page = this.repository.findAll(this.pageRequest);

		//has data, moves cursor to first position
		if (this.page.getNumberOfElements() > 0)
			this.current = 0;

		return this.page.getContent().toArray();
	}

	/** 
	 * Create a new instance of entity and add a 
	 * results and set current (index) for his position
	 */
	public void insert() {
		try {
			this.insertedElement = this.domainClass.newInstance();
		} catch (Exception ex) {
			throw new RuntimeException(ex);
		}
	}

	/** 
	 * Saves the object in the current index
	 */
	public void save() {
		Object toSave;
		if (this.insertedElement != null) {
			toSave = this.insertedElement;
			this.insertedElement = null;
		} else
			toSave = this.getObject();
		this.repository.save(toSave);
	}

	/** 
	 * Removes the object in the current index
	 */
	public void delete() {
		Object toRemove = this.getObject();
		this.repository.delete(toRemove);
	}

	/** 
	 * Removes objects by query
	 * 
	 * @param query - JPQL instruction for filter objects to remove
	 * @param params - Bidimentional array with params name and params value
	 */
	public void delete(String query, Object[][] params) {
		try {
			RepositoryUtil ru = (RepositoryUtil) ApplicationContextHolder.getContext().getBean("repositoryUtil");
			EntityManager em = ru.getEntityManager(domainClass);
			TypedQuery<?> deleteQuery = em.createQuery(filter, domainClass);

			for (Object[] p : this.params) {
				deleteQuery.setParameter(p[0].toString(), p[1]);
			}
			deleteQuery.executeUpdate();
		} catch (Exception ex) {
			throw new RuntimeException(Messages.format(Messages.getString("DATASOURCE_INVALID_QUERY"), filter));
		}
	}

	/** 
	 * Update a field from object in the current index
	 *  
	 * @param fieldName - attributte name in entity
	 * @param fieldValue - value that replaced or inserted in field name passed
	 */
	public void updateField(String fieldName, Object fieldValue) {
		try {
			Method setMethod = Utils.findMethod(getObject(), "set" + fieldName);
			if (setMethod != null) {
				setMethod.invoke(getObject(), fieldValue);
			}
		} catch (Exception ex) {
			throw new RuntimeException(ex);
		}
	}

	/** 
	 * Update fields from object in the current index
	 *  
	 * @param fields - bidimensional array like fields
	 * sample: { {"name", "Paul"}, {"age", "21"} }
	 * 
	 * @thows RuntimeException if a field is not accessible through a set method
	 */
	public void updateFields(Object[][] fields) {
		try {
			for (Object[] oArray : fields) {
				Method setMethod = Utils.findMethod(getObject(), "set" + oArray[0]);
				if (setMethod != null) {
					setMethod.invoke(getObject(), oArray[1]);
				}
			}
		} catch (Exception ex) {
			throw new RuntimeException(ex);
		}
	}

	/** 
	 * Update fields from object in the current index
	 * 
	 * @param query - JPQL instruction for filter objects to update
	 * @param fields - bidimensional array like fields
	 * sample: { {"name", "Paul"}, {"age", "21"} }
	 * 
	 * @thows RuntimeException if a field is not accessible through a set method
	 */
	public void updateFields(String query, Object[][] fields) {
		try {
			RepositoryUtil ru = (RepositoryUtil) ApplicationContextHolder.getContext().getBean("repositoryUtil");
			EntityManager em = ru.getEntityManager(domainClass);
			TypedQuery<?> updateQuery = em.createQuery(filter, domainClass);
			for (Object[] p : this.params) {
				updateQuery.setParameter(p[0].toString(), p[1]);
			}
			updateQuery.executeUpdate();
		} catch (Exception ex) {
			throw new RuntimeException(Messages.format(Messages.getString("DATASOURCE_INVALID_QUERY"), filter));
		}
	}

	/** 
	 * Return object in current index
	 * 
	 * @return Object from database in current position
	 */
	public Object getObject() {

		if (this.insertedElement != null)
			return this.insertedElement;

		return this.page.getContent().get(this.current);
	}

	/** 
	 * Return field passed from object in current index
	 * 
	 * @return Object value of field passed
	 * @thows RuntimeException if a field is not accessible through a set method
	 */
	public Object getObject(String fieldName) {
		try {
			Method getMethod = Utils.findMethod(getObject(), "get" + fieldName);
			if (getMethod != null)
				return getMethod.invoke(getObject());
			return null;
		} catch (Exception ex) {
			throw new RuntimeException(ex);
		}
	}

	/**
	 * Moves the index for next position, in pageable case, 
	 * looking for next page and so on 
	 */
	public void next() {
		if (this.page.getNumberOfElements() > (this.current + 1))
			this.current++;
		else {
			if (this.page.hasNext()) {
				this.pageRequest = this.page.nextPageable();
				this.fetch();
				this.current = 0;
			} else {
			}
		}
	}

	/**
	 * Verify if can moves the index for next position, 
	 * in pageable case, looking for next page and so on 
	 * 
	 * @return boolean true if has next, false else
	 */
	public boolean hasNext() {
		if (this.page.getNumberOfElements() > (this.current + 1))
			return true;
		else {
			if (this.page.hasNext()) {
				return true;
			} else {
				return false;
			}
		}
	}

	/**
	 * Moves the index for previous position, in pageable case, 
	 * looking for next page and so on 
	 * 
	 * @return boolean true if has previous, false else
	 */
	public boolean previous() {
		if (this.current - 1 >= 0) {
			this.current--;
		} else {
			if (this.page.hasPrevious()) {
				this.pageRequest = this.page.previousPageable();
				this.fetch();
				this.current = this.page.getNumberOfElements() - 1;
			} else {
				return false;
			}
		}
		return true;
	}

	/**
	 * Gets a Pageable object retrieved from repository
	 * 
	 * @return pageable from repository, returns null when fetched by filter
	 */
	public Page getPage() {
		return this.page;
	}

	/**
	 * Create a new page request with size passed
	 * 
	 * @param pageSize size of page request
	 */
	public void setPageSize(int pageSize) {
		this.pageSize = pageSize;
		this.pageRequest = new PageRequest(0, pageSize);
		this.current = -1;
	}

	/**
	 * Fetch objects from database by a filter
	 * 
	 * @param filter jpql instruction like a namedQuery
	 * @param params parameters used in jpql instruction
	 */
	public void filter(String filter, Object[][] params) {
		this.filter = filter;
		this.params = params;
		this.pageRequest = new PageRequest(0, pageSize);
		this.current = -1;
		this.fetch();
	}

	/**
	 * Clean Datasource and to free up allocated memory
	 */
	public void clear() {
		this.pageRequest = new PageRequest(0, 100);
		this.current = -1;
		this.page = null;
	}

}
