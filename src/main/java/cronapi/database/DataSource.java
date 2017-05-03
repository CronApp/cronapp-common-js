package cronapi.database;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import javax.persistence.EntityManager;
import javax.persistence.Parameter;
import javax.persistence.TypedQuery;

import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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
	private Object[] params;
	private List<Object> results;
	private int pageSize;
	private Page page;
	private int index;
	private int current;
	private JpaRepository<Object, String> repository;
	private PageRequest pageRequest;

	/** 
	 * Init a datasource with a page size equals 50
	 * 
	 * @param entity - full name of entitiy class like String  
	 */ 
	public DataSource(String entity) {
		this(entity, 50);
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
		this.results = new ArrayList<Object>();
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
						String.format(Messages.getString("REPOSITORY_NOT_FOUND"), this.entity)));
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
			RepositoryUtil ru = (RepositoryUtil) ApplicationContextHolder.getContext().getBean("repositoryUtil");
			EntityManager em = ru.getEntityManager(domainClass);
			TypedQuery<?> query = em.createQuery(filter, domainClass);
			int i = 0;
			for (Object p : query.getParameters().toArray()) {
				query.setParameter(((Parameter) p).getName(), this.params[i]);
				i++;
			}
			this.results.addAll(query.getResultList());
			this.page = null;
		} else
			this.page = this.repository.findAll(this.pageRequest);

		this.results.addAll(this.page.getContent());
		this.current = -1;
		return this.page.getContent().toArray();
	}

	/** 
	 * Create a new instance of entity and add a 
	 * results and set current (index) for his position
	 */
	public void insert() {
		try {
			this.results.add(this.domainClass.newInstance());
			this.current = this.results.size() - 1;
		} catch (Exception ex) {
			throw new RuntimeException(ex);
		}
	}

  /** 
   * Saves the object in the current index
   */ 
	public void save() {
		Object toSave = this.getObject();
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
   * Return object in current index
   * 
   * @return Object from database in current position
   */ 
	public Object getObject() {
		return this.results.get(this.current);
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
   * 
   * @return boolean true if has next, false else
   */
	public boolean next() {
		if (this.results.size() > (this.current + 1))
			this.current++;
		else {
			if (this.page == null)
				return false;
			else if (this.page.hasNext()) {
				this.page = this.repository.findAll(this.page.nextPageable());
				this.results.addAll(this.page.getContent());
				this.current++;
			} else {
				return false;
			}
		}
		return true;
	}

  /**
   * Moves the index for previous position, in pageable case, 
   * looking for next page and so on 
   * 
   * @return boolean true if has previous, false else
   */
	public boolean previous() {
		if (this.current > 0) {
			this.current--;
			return true;
		}
		return false;
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
		this.results.clear();
	}

  /**
   * Fetch objects from database by a filter
   * 
   * @param filter jpql instruction like a namedQuery
   * @param params parameters used in jpql instruction
   */
	public void filter(String filter, Object... params) {
		this.filter = filter;
		this.params = params;
		this.fetch();
	}
}
