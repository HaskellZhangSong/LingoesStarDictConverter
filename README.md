# LingoesStarDictConverter

这个项目会把灵格斯Lingoes的LD2文件转制成星际王StarDict的格式。只是改动了Xiaoyun Zhu的源码，英文、德文字典测试可以使用，俄文好像有编码问题，未来会改进。

# 使用

源码中的main函数里的ld2File改成你ld2的路径，然后会输出4个文件：

## .inflated

解压文件

## .ifo
星际王词典信息文件

## .idx
星际王词典文件

## .dict
星际王词典的释意文件

## ifo, idx, dict就是星际王需要的3个文件。
