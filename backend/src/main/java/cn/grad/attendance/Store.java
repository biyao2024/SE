package cn.grad.attendance;

import jakarta.persistence.*;
import java.util.*;
import org.springframework.stereotype.Repository;

@Repository
public class Store {
    @PersistenceContext EntityManager em;
    public <T> T save(T row) { em.persist(row); return row; }
    public <T> T get(Class<T> cls,Long id) { T row=em.find(cls,id); if(row==null) throw Api.error(404,"NOT_FOUND","记录不存在"); return row; }
    public <T> T lock(Class<T> cls,Long id) { T row=em.find(cls,id,LockModeType.PESSIMISTIC_WRITE); if(row==null) throw Api.error(404,"NOT_FOUND","记录不存在"); return row; }
    public <T> T lockFirst(Class<T> cls) { return em.createQuery("select e from "+cls.getSimpleName()+" e order by e.id",cls).setLockMode(LockModeType.PESSIMISTIC_WRITE).setMaxResults(1).getSingleResult(); }
    public <T> List<T> list(Class<T> cls,String tail,Object... args) {
        var q=em.createQuery("select e from "+cls.getSimpleName()+" e "+tail,cls);
        for(int i=0;i<args.length;i++) q.setParameter(i+1,args[i]);
        return q.getResultList();
    }
    public <T> Optional<T> first(Class<T> cls,String tail,Object...args) { return list(cls,tail,args).stream().findFirst(); }
    public void remove(Object row) { em.remove(row); }
    public void flush() { em.flush(); }
}
