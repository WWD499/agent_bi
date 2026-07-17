package com.bi.agent.vo;

import java.util.List;

/**
 * 通用分页结果 VO（与前端分页组件对齐）。
 *
 * @param <T> 单页数据元素类型
 */
public class PageResult<T> {

    /** 当前页数据 */
    private List<T> list;
    /** 总数 */
    private long total;
    /** 当前页（从 0 开始） */
    private int page;
    /** 每页大小 */
    private int size;

    public PageResult() {
    }

    public PageResult(List<T> list, long total, int page, int size) {
        this.list = list;
        this.total = total;
        this.page = page;
        this.size = size;
    }

    public List<T> getList() {
        return list;
    }

    public void setList(List<T> list) {
        this.list = list;
    }

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public int getSize() {
        return size;
    }

    public void setSize(int size) {
        this.size = size;
    }
}
