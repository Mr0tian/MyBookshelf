package com.monke.monkeybook.model.content;

import com.monke.basemvplib.BaseModelImpl;
import com.monke.monkeybook.MApplication;
import com.monke.monkeybook.R;
import com.monke.monkeybook.base.observer.SimpleObserver;
import com.monke.monkeybook.bean.BookContentBean;
import com.monke.monkeybook.bean.BookInfoBean;
import com.monke.monkeybook.bean.BookShelfBean;
import com.monke.monkeybook.bean.BookSourceBean;
import com.monke.monkeybook.bean.ChapterListBean;
import com.monke.monkeybook.bean.SearchBookBean;
import com.monke.monkeybook.bean.WebChapterBean;
import com.monke.monkeybook.dao.BookSourceBeanDao;
import com.monke.monkeybook.dao.DbHelper;
import com.monke.monkeybook.help.AnalyzeSearchUrl;
import com.monke.monkeybook.help.FormatWebText;
import com.monke.monkeybook.listener.OnGetChapterListListener;
import com.monke.monkeybook.model.ErrorAnalyContentManager;
import com.monke.monkeybook.model.impl.IGetWebApi;
import com.monke.monkeybook.model.impl.IStationBookModel;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

import static android.text.TextUtils.isEmpty;

/**
 * 默认检索规则
 */
public class DefaultModelImpl extends BaseModelImpl implements IStationBookModel {
    private String TAG;
    private String name;
    private BookSourceBean bookSourceBean;

    public static DefaultModelImpl getInstance(String tag) {
        return new DefaultModelImpl(tag);
    }

    private DefaultModelImpl(String tag) {
        TAG = tag;
        try {
            URL url = new URL(tag);
            name = url.getHost();
        } catch (MalformedURLException e) {
            e.printStackTrace();
            name = tag;
        }
    }

    private void initBookSourceBean() {
        if (bookSourceBean == null) {
            List<BookSourceBean> bookSourceBeans = DbHelper.getInstance().getmDaoSession().getBookSourceBeanDao().queryBuilder().where(BookSourceBeanDao.Properties.BookSourceUrl.eq(TAG)).build().list();
            if (bookSourceBeans != null && bookSourceBeans.size() > 0) {
                bookSourceBean = bookSourceBeans.get(0);
            }
        }
    }

    /**
     * 搜索
     */
    @Override
    public Observable<List<SearchBookBean>> searchBook(String content, int page) {
        initBookSourceBean();
        if (bookSourceBean == null) {
            return null;
        }
        try {
            AnalyzeSearchUrl analyzeSearchUrl = new AnalyzeSearchUrl(bookSourceBean.getRuleSearchUrl(), content, page);
            return getRetrofitString(analyzeSearchUrl.getSearchUrl())
                    .create(IGetWebApi.class)
                    .searchBook(analyzeSearchUrl.getSearchPath(), analyzeSearchUrl.getQueryMap())
                    .flatMap(this::analyzeSearchBook);
        } catch (MalformedURLException e) {
            e.printStackTrace();
            return null;
        }
    }

    private Observable<List<SearchBookBean>> analyzeSearchBook(final String s) {
        return Observable.create(e -> {
            try {
                Document doc = Jsoup.parse(s);
                Elements booksE = doc.getElementsByClass(bookSourceBean.getRuleSearchList());
                if (null != booksE && booksE.size() > 0) {
                    List<SearchBookBean> books = new ArrayList<>();
                    for (int i = 0; i < booksE.size(); i++) {
                        SearchBookBean item = new SearchBookBean();
                        item.setTag(TAG);
                        item.setOrigin(name);
                        AnalyzeSearchRule analyzeSearchRule = new AnalyzeSearchRule(booksE.get(i));
                        item.setAuthor(FormatWebText.getAuthor(analyzeSearchRule.getResult(bookSourceBean.getRuleSearchAuthor())));
                        item.setKind(analyzeSearchRule.getResult(bookSourceBean.getRuleSearchKind()));
                        item.setLastChapter(analyzeSearchRule.getResult(bookSourceBean.getRuleSearchLastChapter()));
                        item.setName(analyzeSearchRule.getResult(bookSourceBean.getRuleSearchName()));
                        item.setNoteUrl(analyzeSearchRule.getResult(bookSourceBean.getRuleSearchNoteUrl()));
                        item.setCoverUrl(analyzeSearchRule.getResult(bookSourceBean.getRuleSearchCoverUrl()));
                        books.add(item);
                    }
                    e.onNext(books);
                } else {
                    e.onNext(new ArrayList<>());
                }
            } catch (Exception ex) {
                ex.printStackTrace();
                e.onNext(new ArrayList<>());
            }
            e.onComplete();
        });
    }

    /**
     * 获取书籍信息
     */
    @Override
    public Observable<BookShelfBean> getBookInfo(final BookShelfBean bookShelfBean) {
        initBookSourceBean();
        return getRetrofitString(TAG)
                .create(IGetWebApi.class)
                .getWebContent(bookShelfBean.getNoteUrl().replace(TAG, ""))
                .flatMap(s -> analyzeBookInfo(s, bookShelfBean));
    }

    private Observable<BookShelfBean> analyzeBookInfo(String s, final BookShelfBean bookShelfBean) {
        return Observable.create(e -> {
            bookShelfBean.setTag(TAG);
            BookInfoBean bookInfoBean = bookShelfBean.getBookInfoBean();
            if (bookInfoBean == null) {
                bookInfoBean = new BookInfoBean();
            }
            bookInfoBean.setNoteUrl(bookShelfBean.getNoteUrl());   //id
            bookInfoBean.setTag(TAG);
            Document doc = Jsoup.parse(s);
            Element resultE = doc.getElementsByClass("box_con").get(0);
            if ((isEmpty(bookInfoBean.getCoverUrl()))) {
                bookInfoBean.setCoverUrl(resultE.getElementById("fmimg").getElementsByTag("img").get(0).attr("src"));
            }
            bookInfoBean.setName(resultE.getElementById("info").getElementsByTag("h1").get(0).text());
            String author = resultE.getElementById("info").getElementsByTag("p").get(0).text().trim();
            author = author.replace(" ", "").replace("  ", "").replace("作者：", "");
            bookInfoBean.setAuthor(author);

            Elements contentEs = resultE.getElementById("intro").getElementsByTag("p");
            StringBuilder content = new StringBuilder();
            for (int i = 0; i < contentEs.size(); i++) {
                String temp = contentEs.get(i).text().trim();
                temp = temp.replaceAll(" ", "").replaceAll(" ", "")
                        .replaceAll("\r","").replaceAll("\n", "").replaceAll("\t", "");
                if (temp.length() > 0) {
                    if (content.length() > 0) {
                        content.append("\r\n");
                    }
                    content.append("\u3000\u3000").append(temp);
                }
            }

            bookInfoBean.setIntroduce(content.toString());
            bookInfoBean.setChapterUrl(bookShelfBean.getNoteUrl());
            bookInfoBean.setOrigin(name);
            bookShelfBean.setBookInfoBean(bookInfoBean);
            e.onNext(bookShelfBean);
            e.onComplete();
        });
    }

    /**
     * 获取目录
     */
    @Override
    public void getChapterList(final BookShelfBean bookShelfBean, final OnGetChapterListListener getChapterListListener) {
        getRetrofitString(TAG)
                .create(IGetWebApi.class)
                .getWebContent(bookShelfBean.getBookInfoBean().getChapterUrl().replace(TAG, ""))
                .flatMap(s -> analyzeChapterList(s, bookShelfBean))
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new SimpleObserver<WebChapterBean<BookShelfBean>>() {
                    @Override
                    public void onNext(WebChapterBean<BookShelfBean> value) {
                        if (getChapterListListener != null) {
                            getChapterListListener.success(value.getData());
                        }
                    }

                    @Override
                    public void onError(Throwable e) {
                        e.printStackTrace();
                        if (getChapterListListener != null) {
                            getChapterListListener.error();
                        }
                    }
                });
    }

    private Observable<WebChapterBean<BookShelfBean>> analyzeChapterList(final String s, final BookShelfBean bookShelfBean) {
        return Observable.create(e -> {
            bookShelfBean.setTag(TAG);
            WebChapterBean<List<ChapterListBean>> temp = analyzeChapterList(s, bookShelfBean.getNoteUrl());
            bookShelfBean.getBookInfoBean().setChapterList(temp.getData());
            e.onNext(new WebChapterBean<>(bookShelfBean, temp.getNext()));
            e.onComplete();
        });
    }

    private WebChapterBean<List<ChapterListBean>> analyzeChapterList(String s, String novelUrl) {
        Document doc = Jsoup.parse(s);
        Elements chapterlist = doc.getElementById("list").getElementsByTag("dd");
        List<ChapterListBean> chapterBeans = new ArrayList<>();
        for (int i = 0; i < chapterlist.size(); i++) {
            ChapterListBean temp = new ChapterListBean();
            temp.setDurChapterUrl(TAG + chapterlist.get(i).getElementsByTag("a").get(0).attr("href"));   //id
            temp.setDurChapterIndex(i);
            temp.setDurChapterName(chapterlist.get(i).getElementsByTag("a").get(0).text());
            temp.setNoteUrl(novelUrl);
            temp.setTag(TAG);

            chapterBeans.add(temp);
        }
        return new WebChapterBean<>(chapterBeans, false);
    }

    /**
     * 获取正文
     */
    @Override
    public Observable<BookContentBean> getBookContent(final String durChapterUrl, final int durChapterIndex) {
        return getRetrofitString(TAG)
                .create(IGetWebApi.class)
                .getWebContent(durChapterUrl.replace(TAG, ""))
                .flatMap(s -> analyzeBookContent(s, durChapterUrl, durChapterIndex));
    }

    private Observable<BookContentBean> analyzeBookContent(final String s, final String durChapterUrl, final int durChapterIndex) {
        return Observable.create(e -> {
            BookContentBean bookContentBean = new BookContentBean();
            bookContentBean.setDurChapterIndex(durChapterIndex);
            bookContentBean.setDurChapterUrl(durChapterUrl);
            bookContentBean.setTag(TAG);
            try {
                Document doc = Jsoup.parse(s);
                List<TextNode> contentEs = doc.getElementById("content").textNodes();
                StringBuilder content = new StringBuilder();
                for (int i = 0; i < contentEs.size(); i++) {
                    String temp = contentEs.get(i).text().trim();
                    temp = temp.replaceAll(" ", "").replaceAll(" ", "");
                    if (temp.length() > 0) {
                        if (content.length() > 0) {
                            content.append("\r\n");
                        }
                        content.append("\u3000\u3000").append(temp);
                    }
                }
                bookContentBean.setDurChapterContent(content.toString());
                bookContentBean.setRight(true);
            } catch (Exception ex) {
                ex.printStackTrace();
                ErrorAnalyContentManager.getInstance().writeNewErrorUrl(durChapterUrl);
                bookContentBean.setDurChapterContent(durChapterUrl.substring(0, durChapterUrl.indexOf('/', 8)) + MApplication.getInstance().getString(R.string.analyze_error));
                bookContentBean.setRight(false);
            }
            e.onNext(bookContentBean);
            e.onComplete();
        });
    }

    /////////////////////////////////////////////////////////////////////////////////////////////////////////////
}
